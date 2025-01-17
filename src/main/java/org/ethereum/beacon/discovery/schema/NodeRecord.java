/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.schema;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.rlp.RLP;
import org.apache.tuweni.rlp.RLPWriter;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Ethereum Node Record V4
 *
 * <p>Node record as described in <a href="https://eips.ethereum.org/EIPS/eip-778">EIP-778</a>
 */
public class NodeRecord {

  /**
   * The canonical encoding of a node record is an RLP list of [signature, seq, k, v, ...]. The
   * maximum encoded size of a node record is 300 bytes. Implementations should reject records
   * larger than this size.
   */
  public static final int MAX_ENCODED_SIZE = 300;

  private static final EnrFieldInterpreter enrFieldInterpreter = EnrFieldInterpreterV4.DEFAULT;
  private final UInt64 seq;
  // Signature
  private Bytes signature;
  // optional fields
  private final Map<String, Object> fields = new HashMap<>();
  private final IdentitySchemaInterpreter identitySchemaInterpreter;

  private NodeRecord(
      IdentitySchemaInterpreter identitySchemaInterpreter, UInt64 seq, Bytes signature) {
    this.seq = seq;
    this.signature = signature;
    this.identitySchemaInterpreter = identitySchemaInterpreter;
  }

  private NodeRecord(IdentitySchemaInterpreter identitySchemaInterpreter, UInt64 seq) {
    this.seq = seq;
    this.signature = MutableBytes.create(96);
    this.identitySchemaInterpreter = identitySchemaInterpreter;
  }

  public static NodeRecord fromValues(
      IdentitySchemaInterpreter identitySchemaInterpreter,
      UInt64 seq,
      List<EnrField> fieldKeyPairs) {
    NodeRecord nodeRecord = new NodeRecord(identitySchemaInterpreter, seq);
    fieldKeyPairs.forEach(objects -> nodeRecord.set(objects.getName(), objects.getValue()));
    return nodeRecord;
  }

  public static NodeRecord fromRawFields(
      IdentitySchemaInterpreter identitySchemaInterpreter,
      UInt64 seq,
      Bytes signature,
      Map<String, Object> rawFields) {
    NodeRecord nodeRecord = new NodeRecord(identitySchemaInterpreter, seq, signature);
    rawFields.forEach((key, value) -> nodeRecord.set(key, enrFieldInterpreter.decode(key, value)));
    return nodeRecord;
  }

  public String asBase64() {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(serialize().toArray());
  }

  public String asEnr() {
    return "enr:" + asBase64();
  }

  public IdentitySchema getIdentityScheme() {
    return identitySchemaInterpreter.getScheme();
  }

  public void set(String key, Object value) {
    fields.put(key, value);
  }

  public Object get(String key) {
    return fields.get(key);
  }

  public void forEachField(BiConsumer<String, Object> consumer) {
    fields.forEach(consumer);
  }

  public boolean containsKey(String key) {
    return fields.containsKey(key);
  }

  public UInt64 getSeq() {
    return seq;
  }

  public Bytes getSignature() {
    return signature;
  }

  public void setSignature(Bytes signature) {
    this.signature = signature;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NodeRecord that = (NodeRecord) o;
    return Objects.equals(seq, that.seq)
        && Objects.equals(signature, that.signature)
        && Objects.equals(fields, that.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(seq, signature, fields);
  }

  public boolean isValid() {
    return identitySchemaInterpreter.isValid(this);
  }

  public void sign(Bytes privateKey) {
    identitySchemaInterpreter.sign(this, privateKey);
  }

  public void writeRlp(final RLPWriter writer) {
    writeRlp(writer, true);
  }

  public void writeRlp(final RLPWriter writer, final boolean includeSignature) {
    Preconditions.checkNotNull(getSeq(), "Missing sequence number");
    // content   = [seq, k, v, ...]
    // signature = sign(content)
    // record    = [signature, seq, k, v, ...]
    List<String> keySortedList = fields.keySet().stream().sorted().collect(Collectors.toList());
    writeRlp(writer, includeSignature, keySortedList);
  }

  @VisibleForTesting
  void writeRlp(
      final RLPWriter writer, final boolean includeSignature, final List<String> keySortedList) {
    writer.writeList(
        listWriter -> {
          if (includeSignature) {
            listWriter.writeValue(getSignature());
          }
          listWriter.writeBigInteger(getSeq().toBigInteger());

          for (String key : keySortedList) {
            if (fields.get(key) == null) {
              continue;
            }
            listWriter.writeString(key);
            enrFieldInterpreter.encode(listWriter, key, fields.get(key));
          }
        });
  }

  public Bytes asRlp() {
    return asRlpImpl(true);
  }

  public Bytes asRlpNoSignature() {
    return asRlpImpl(false);
  }

  private Bytes asRlpImpl(boolean withSignature) {
    return RLP.encode(writer -> writeRlp(writer, withSignature));
  }

  public Bytes serialize() {
    return serializeImpl(true);
  }

  public Bytes serializeNoSignature() {
    return serializeImpl(false);
  }

  private Bytes serializeImpl(boolean withSignature) {
    Bytes bytes = withSignature ? asRlp() : asRlpNoSignature();
    checkArgument(bytes.size() <= MAX_ENCODED_SIZE, "Node record exceeds maximum encoded size");
    return bytes;
  }

  public Bytes getNodeId() {
    return identitySchemaInterpreter.getNodeId(this);
  }

  public Optional<InetSocketAddress> getTcpAddress() {
    return identitySchemaInterpreter.getTcpAddress(this);
  }

  public Optional<InetSocketAddress> getUdpAddress() {
    return identitySchemaInterpreter.getUdpAddress(this);
  }

  public NodeRecord withNewAddress(
      final InetSocketAddress newUdpAddress,
      final Optional<Integer> newTcpPort,
      final Bytes privateKey) {
    return identitySchemaInterpreter.createWithNewAddress(
        this, newUdpAddress, newTcpPort, privateKey);
  }

  public NodeRecord withUpdatedCustomField(
      final String fieldName, final Bytes value, final Bytes privateKey) {
    return identitySchemaInterpreter.createWithUpdatedCustomField(
        this, fieldName, value, privateKey);
  }

  @Override
  public String toString() {
    return "NodeRecord{"
        + "seq="
        + seq
        + ", publicKey="
        + fields.get(EnrField.PKEY_SECP256K1)
        + ", udpAddress="
        + getUdpAddress()
        + ", tcpAddress="
        + getTcpAddress()
        + ", asBase64="
        + this.asBase64()
        + ", nodeId="
        + this.getNodeId()
        + ", customFields="
        + fields
        + '}';
  }
}
