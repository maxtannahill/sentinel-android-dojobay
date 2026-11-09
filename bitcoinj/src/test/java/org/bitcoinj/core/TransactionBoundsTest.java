package org.bitcoinj.core;

import org.bitcoinj.params.UnitTestParams;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A short payload must not be able to size an input list, an output list,
 * or a script array from the length it declares.
 */
public class TransactionBoundsTest {
    private static final NetworkParameters PARAMS = UnitTestParams.get();

    @Before
    public void setUp() {
        new Context(PARAMS);
    }

    @Test
    public void ordinaryTransactionStillParses() {
        Transaction tx = new Transaction(PARAMS);
        tx.addInput(new TransactionInput(
                PARAMS,
                tx,
                new byte[0],
                new TransactionOutPoint(PARAMS, 0, Sha256Hash.wrap(
                        "1111111111111111111111111111111111111111111111111111111111111111"))));
        tx.addOutput(new TransactionOutput(PARAMS, tx, Coin.valueOf(10_000), new byte[] {0x51}));
        Transaction parsed = new Transaction(PARAMS, tx.bitcoinSerialize());
        assertEquals(1, parsed.getInputs().size());
        assertEquals(1, parsed.getOutputs().size());
        assertEquals(10_000L, parsed.getOutput(0).getValue().value);
    }

    @Test
    public void hugeInputCountIsRejected() {
        ProtocolException ex = assertRejected("01000000feffffff7f");
        assertTrue(ex.getMessage(), ex.getMessage().contains("Too many transaction inputs"));
    }

    @Test
    public void hugeOutputCountIsRejected() {
        // One empty-script input, so a leading zero is not read as a witness flag.
        StringBuilder payload = new StringBuilder("0100000001");
        for (int i = 0; i < 36; i++) {
            payload.append("00");
        }
        payload.append("00fffffffffeffffff7f");
        ProtocolException ex = assertRejected(payload.toString());
        assertTrue(ex.getMessage(), ex.getMessage().contains("Too many transaction outputs"));
    }

    @Test
    public void scriptLongerThanThePayloadIsRejected() {
        StringBuilder payload = new StringBuilder("0100000001");
        for (int i = 0; i < 36; i++) {
            payload.append("00");
        }
        payload.append("fe00000001");
        ProtocolException ex = assertRejected(payload.toString());
        assertTrue(ex.getMessage(), ex.getMessage().contains("Claimed value length too large"));
    }

    @Test
    public void highBitLengthIsRejected() {
        ProtocolException ex = assertRejected("01000000ffffffffffffffffff");
        assertTrue(ex.getMessage(), ex.getMessage().contains("Too many transaction inputs"));
    }

    private static ProtocolException assertRejected(String hex) {
        try {
            new Transaction(PARAMS, Hex.decode(hex));
            fail("expected ProtocolException");
            return null;
        } catch (ProtocolException ex) {
            return ex;
        }
    }
}
