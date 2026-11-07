package com.samourai.wallet.psbt;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.TestNet3Params;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BIP-174 ends the global map on the first 0x00 separator. These tests lock
 * that boundary, the single unsigned-tx global this writer emits, and
 * rejection of truncated or trailing payloads.
 */
public class PSBTReadTest {

    private static final NetworkParameters PARAMS = TestNet3Params.get();
    private static final byte[] PUB = Hex.decode(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
    private static final byte[] XPUB_MARKER = new byte[] {(byte) 0xDE, (byte) 0xAD};
    private static final byte[] CANARY = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    @BeforeAll
    public static void bitcoinjContext() {
        new Context(PARAMS);
    }

    @Test
    public void roundTrip_oneGlobal_preservesUnsignedTxAndSections() throws Exception {
        Fixture fixture = fixture();

        assertEquals(1, countGlobals(fixture.bytes));
        int separator = globalSeparator(fixture.bytes);
        assertEquals(0x00, fixture.bytes[separator] & 0xff);
        assertArrayEquals(fixture.tx.bitcoinSerialize(), unsignedTx(fixture.bytes));
        assertFalse(contains(fixture.bytes, XPUB_MARKER));

        PSBT parsed = PSBT.fromBytes(fixture.bytes, PARAMS);
        assertTrue(parsed.isParseOK());
        assertArrayEquals(fixture.bytes, parsed.toBytes());
        assertArrayEquals(fixture.tx.bitcoinSerialize(), parsed.getTransaction().bitcoinSerialize());
        assertEquals(0, parsed.getTransaction().getInput(0).getScriptBytes().length);
        assertEquals(0, parsed.getTransaction().getInput(1).getScriptBytes().length);
        assertEquals(fixture.tx.getInputs().size(), parsed.getInputCount());
        assertEquals(fixture.tx.getOutputs().size(), parsed.getOutputCount());
        assertEntries(parsed.getPsbtInputs(), fixture.inputs);
        assertEntries(parsed.getPsbtOutputs(), fixture.outputs);
    }

    @Test
    public void read_threeAndFourGlobals_keepInputAndOutputSections() throws Exception {
        Fixture fixture = fixture();
        int separator = globalSeparator(fixture.bytes);

        byte[] three = insert(fixture.bytes, separator, concat(
                kv(PSBT.PSBT_GLOBAL_XPUB, xpubKey(), new byte[] {0x11, 0x22, 0x33, 0x44}),
                kv(PSBT.PSBT_GLOBAL_VERSION, null, new byte[] {0x00, 0x00, 0x00, 0x00})
        ));
        assertEquals(3, countGlobals(three));
        assertSameSections(fixture, three);

        byte[] four = insert(three, globalSeparator(three),
                kv((byte) 0x02, null, CANARY));
        assertEquals(4, countGlobals(four));
        assertSameSections(fixture, four);
        PSBT parsed = PSBT.fromBytes(four, PARAMS);
        assertFalse(containsEntry(parsed.getPsbtInputs(), CANARY));
        assertFalse(containsEntry(parsed.getPsbtOutputs(), CANARY));
    }

    @Test
    public void read_versionGlobalBeforeUnsignedTx_stillSectionsCorrectly() throws Exception {
        Fixture fixture = fixture();
        byte[] versionFirst = insert(fixture.bytes, 5,
                kv(PSBT.PSBT_GLOBAL_VERSION, null, new byte[] {0x02, 0x00, 0x00, 0x00}));
        assertEquals(2, countGlobals(versionFirst));
        assertSameSections(fixture, versionFirst);
    }

    @Test
    public void read_extraSeparatorsAreRejected() throws Exception {
        Fixture fixture = fixture();
        int separator = globalSeparator(fixture.bytes);

        // A second 0x00 is an empty input map, not another global terminator.
        assertRejected(insert(fixture.bytes, separator, new byte[] {0x00}), "Trailing");

        // Two separator-terminated globals used to satisfy seenGlobals == 2.
        byte[] doubleTerminated = insert(fixture.bytes, separator, concat(
                kv(PSBT.PSBT_GLOBAL_VERSION, null, new byte[] {0x00, 0x00, 0x00, 0x00}),
                new byte[] {0x00}
        ));
        assertRejected(doubleTerminated, "Trailing");

        byte[] three = insert(fixture.bytes, separator, concat(
                kv(PSBT.PSBT_GLOBAL_XPUB, xpubKey(), new byte[] {0x11, 0x22, 0x33, 0x44}),
                kv(PSBT.PSBT_GLOBAL_VERSION, null, new byte[] {0x00, 0x00, 0x00, 0x00})
        ));
        assertRejected(concat(three, new byte[] {0x00}), "Trailing");
        assertRejected(concat(fixture.bytes, new byte[] {0x00}), "Trailing");
        assertRejected(concat(fixture.bytes, kv((byte) 0x02, null, new byte[] {0x11})), "Trailing");
    }

    @Test
    public void read_truncatedPayloadIsRejected() throws Exception {
        Fixture fixture = fixture();
        assertRejected(Hex.decode("70736274"), "Truncated");
        assertRejected(Hex.decode("70736274ff"), "Truncated");
        assertRejected(Hex.decode("70736274ff04"), "Truncated");
        assertRejected(Arrays.copyOf(fixture.bytes, fixture.bytes.length - 1), "Truncated");
        assertRejected(Arrays.copyOf(fixture.bytes, 12), "Truncated");
    }

    private static void assertSameSections(Fixture fixture, byte[] payload) throws Exception {
        PSBT parsed = PSBT.fromBytes(payload, PARAMS);
        assertTrue(parsed.isParseOK());
        assertArrayEquals(fixture.tx.bitcoinSerialize(), parsed.getTransaction().bitcoinSerialize());
        assertArrayEquals(fixture.bytes, parsed.toBytes());
        assertEntries(parsed.getPsbtInputs(), fixture.inputs);
        assertEntries(parsed.getPsbtOutputs(), fixture.outputs);
    }

    private static void assertRejected(byte[] payload, String messagePart) {
        Exception ex = assertThrows(Exception.class, () -> PSBT.fromBytes(payload, PARAMS));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains(messagePart), ex.getMessage());
    }

    private static Fixture fixture() throws Exception {
        Transaction tx = new Transaction(PARAMS);
        tx.addInput(input(tx, 0, "1111111111111111111111111111111111111111111111111111111111111111", 60_000));
        tx.addInput(input(tx, 1, "2222222222222222222222222222222222222222222222222222222222222222", 40_000));
        tx.addOutput(new TransactionOutput(PARAMS, tx, Coin.valueOf(50_000), new byte[] {(byte) 0x51}));
        tx.addOutput(new TransactionOutput(PARAMS, tx, Coin.valueOf(40_000), new byte[] {(byte) 0x52}));

        byte[] utxo0 = new byte[] {(byte) 0xA0, 0x01, 0x02, 0x03};
        byte[] deriv0 = new byte[] {0x11, 0x22, 0x33, 0x44, 0x00, 0x00, 0x00, 0x00};
        byte[] utxo1 = new byte[] {(byte) 0xA1, 0x04, 0x05, 0x06};
        byte[] deriv1 = new byte[] {0x11, 0x22, 0x33, 0x44, 0x01, 0x00, 0x00, 0x00};
        byte[] outDeriv = new byte[] {0x11, 0x22, 0x33, 0x44, 0x02, 0x00, 0x00, 0x00};

        PSBT psbt = new PSBT(tx);
        psbt.addGlobalUnsignedTx(Hex.toHexString(tx.bitcoinSerialize()));
        psbt.addGlobalXpubRecord(xpubKey(), new byte[] {0x11, 0x22, 0x33, 0x44}, 84, 0, 0);
        psbt.addGlobalSeparator();
        psbt.addInput(PSBT.PSBT_IN_WITNESS_UTXO, null, utxo0);
        psbt.addInput(PSBT.PSBT_IN_BIP32_DERIVATION, PUB, deriv0);
        psbt.addInputSeparator();
        psbt.addInput(PSBT.PSBT_IN_WITNESS_UTXO, null, utxo1);
        psbt.addInput(PSBT.PSBT_IN_BIP32_DERIVATION, PUB, deriv1);
        psbt.addInputSeparator();
        psbt.addOutput(PSBT.PSBT_OUT_BIP32_DERIVATION, PUB, outDeriv);
        psbt.addOutputSeparator();
        psbt.addOutputSeparator();

        Fixture fixture = new Fixture();
        fixture.tx = tx;
        fixture.bytes = psbt.toBytes();
        fixture.inputs = new Expect[] {
                Expect.of(PSBT.PSBT_IN_WITNESS_UTXO, utxo0),
                Expect.of(PSBT.PSBT_IN_BIP32_DERIVATION, deriv0),
                Expect.sep(),
                Expect.of(PSBT.PSBT_IN_WITNESS_UTXO, utxo1),
                Expect.of(PSBT.PSBT_IN_BIP32_DERIVATION, deriv1),
                Expect.sep()
        };
        fixture.outputs = new Expect[] {
                Expect.of(PSBT.PSBT_OUT_BIP32_DERIVATION, outDeriv),
                Expect.sep(),
                Expect.sep()
        };
        return fixture;
    }

    private static TransactionInput input(Transaction tx, int index, String prev, long value) {
        return new TransactionInput(
                PARAMS,
                tx,
                new byte[0],
                new TransactionOutPoint(PARAMS, index, Sha256Hash.wrap(prev)),
                Coin.valueOf(value));
    }

    private static byte[] xpubKey() {
        byte[] xpub = new byte[78];
        System.arraycopy(XPUB_MARKER, 0, xpub, 0, XPUB_MARKER.length);
        return xpub;
    }

    private static void assertEntries(List<PSBTEntry> actual, Expect[] expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            PSBTEntry entry = actual.get(i);
            if (expected[i].separator) {
                assertNull(entry.getKey());
            } else {
                assertNotNull(entry.getKey());
                assertEquals(expected[i].type, entry.getKeyType()[0]);
                assertArrayEquals(expected[i].data, entry.getData());
            }
        }
    }

    private static boolean containsEntry(List<PSBTEntry> entries, byte[] data) {
        for (PSBTEntry entry : entries) {
            if (Arrays.equals(data, entry.getData())) {
                return true;
            }
        }
        return false;
    }

    private static int countGlobals(byte[] psbt) {
        Cursor cursor = new Cursor(psbt, 5);
        int globals = 0;
        while (true) {
            int keyLen = cursor.readCompact();
            if (keyLen == 0) {
                return globals;
            }
            cursor.skip(keyLen);
            cursor.skip(cursor.readCompact());
            globals++;
        }
    }

    private static int globalSeparator(byte[] psbt) {
        Cursor cursor = new Cursor(psbt, 5);
        while (true) {
            int at = cursor.i;
            int keyLen = cursor.readCompact();
            if (keyLen == 0) {
                return at;
            }
            cursor.skip(keyLen);
            cursor.skip(cursor.readCompact());
        }
    }

    private static byte[] unsignedTx(byte[] psbt) {
        Cursor cursor = new Cursor(psbt, 5);
        while (true) {
            int keyLen = cursor.readCompact();
            if (keyLen == 0) {
                throw new AssertionError("unsigned tx global missing");
            }
            byte[] key = cursor.read(keyLen);
            byte[] value = cursor.read(cursor.readCompact());
            if (key[0] == PSBT.PSBT_GLOBAL_UNSIGNED_TX) {
                return value;
            }
        }
    }

    private static byte[] kv(byte type, byte[] keyData, byte[] value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int keyLen = 1 + (keyData == null ? 0 : keyData.length);
        out.write(PSBT.writeCompactInt(keyLen));
        out.write(type);
        if (keyData != null) {
            out.write(keyData);
        }
        out.write(PSBT.writeCompactInt(value.length));
        out.write(value);
        return out.toByteArray();
    }

    private static byte[] insert(byte[] psbt, int offset, byte[] extra) {
        byte[] out = new byte[psbt.length + extra.length];
        System.arraycopy(psbt, 0, out, 0, offset);
        System.arraycopy(extra, 0, out, offset, extra.length);
        System.arraycopy(psbt, offset, out, offset + extra.length, psbt.length - offset);
        return out;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        return insert(first, first.length, second);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static final class Fixture {
        Transaction tx;
        byte[] bytes;
        Expect[] inputs;
        Expect[] outputs;
    }

    private static final class Expect {
        final boolean separator;
        final byte type;
        final byte[] data;

        private Expect(boolean separator, byte type, byte[] data) {
            this.separator = separator;
            this.type = type;
            this.data = data;
        }

        static Expect sep() {
            return new Expect(true, (byte) 0, null);
        }

        static Expect of(byte type, byte[] data) {
            return new Expect(false, type, data);
        }
    }

    private static final class Cursor {
        final byte[] buf;
        int i;

        Cursor(byte[] buf, int i) {
            this.buf = buf;
            this.i = i;
        }

        int readCompact() {
            int v = buf[i++] & 0xff;
            if (v < 0xfd) {
                return v;
            }
            if (v == 0xfd) {
                int x = (buf[i] & 0xff) | ((buf[i + 1] & 0xff) << 8);
                i += 2;
                return x;
            }
            throw new AssertionError("compact size too wide at " + (i - 1));
        }

        byte[] read(int n) {
            byte[] out = new byte[n];
            System.arraycopy(buf, i, out, 0, n);
            i += n;
            return out;
        }

        void skip(int n) {
            i += n;
        }
    }
}
