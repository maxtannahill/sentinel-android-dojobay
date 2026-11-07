package com.samourai.wallet.send;

import com.samourai.wallet.SamouraiWalletConst;
import com.samourai.wallet.bipFormat.BIP_FORMAT;
import com.samourai.wallet.bipFormat.BipFormatSupplier;
import com.samourai.wallet.send.exceptions.MakeTxException;
import com.samourai.wallet.util.FormatsUtilGeneric;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.MainNetParams;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #463: makeTransaction rejects a spendable output below
 * {@link SamouraiWalletConst#bDust} and still builds a transaction for a
 * payable amount or a BIP47 OP_RETURN.
 */
public class SendFactoryGenericDustTest {

    private static final NetworkParameters PARAMS = MainNetParams.get();
    private static final BipFormatSupplier BIP = BIP_FORMAT.PROVIDER;
    private static final String BECH32 = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
    private static final String LEGACY = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
    private static final long BLOCK_HEIGHT = 750_000L;
    private static final String TXID = "598cbf9f11ab9a1a5e788dbd11a7cf970089cec43e04fc073eb91c0a5717fd0e";

    @Test
    public void dustConstantIsTheSendStackMinimum() {
        assertEquals(546L, SamouraiWalletConst.bDust.longValue());
    }

    @Test
    public void dustSizedOutputIsRejected() {
        long below = SamouraiWalletConst.bDust.longValue() - 1L;
        assertDust(singleton(BECH32, below));
        assertDust(singleton(BECH32, 1L));
        assertDust(singleton(LEGACY, below));
    }

    @Test
    public void dustSizedOutputIsRejectedThroughTheBigIntegerEntry() {
        Map<String, BigInteger> receivers = new LinkedHashMap<String, BigInteger>();
        receivers.put(BECH32, BigInteger.ONE);
        MakeTxException error = assertThrows(MakeTxException.class, () ->
                SendFactoryGeneric.getInstance().makeTransaction(
                        unspent(), receivers, BIP, false, PARAMS, BLOCK_HEIGHT));
        assertEquals("Dust amount", error.getMessage());
    }

    @Test
    public void thresholdAndLargerAmountsCompose() throws Exception {
        Transaction atThreshold = compose(singleton(BECH32, SamouraiWalletConst.bDust.longValue()));
        assertPayment(atThreshold, SamouraiWalletConst.bDust.longValue());

        Transaction larger = compose(singleton(LEGACY, 100_000L));
        assertPayment(larger, 100_000L);
        assertEquals(1, larger.getInputs().size());
        assertUntouchedLocktimeRule(larger);
    }

    @Test
    public void zeroAmountStaysInvalidRatherThanDust() {
        MakeTxException error = assertThrows(MakeTxException.class, () -> compose(singleton(BECH32, 0L)));
        assertEquals("Invalid amount", error.getMessage());
    }

    @Test
    public void bip47OpReturnStillComposes() throws Exception {
        assertTrue(FormatsUtilGeneric.getInstance().isValidBIP47OpReturn(bip47OpReturn()));

        Transaction alone = compose(singleton(bip47OpReturn(), 0L));
        TransactionOutput opReturn = opReturnOutput(alone);
        assertNotNull(opReturn);
        assertEquals(0L, opReturn.getValue().value);
        assertEquals(1, alone.getOutputs().size());
        assertUntouchedLocktimeRule(alone);

        Map<String, Long> withPayment = new LinkedHashMap<String, Long>();
        withPayment.put(bip47OpReturn(), 0L);
        withPayment.put(BECH32, 50_000L);
        Transaction both = compose(withPayment);
        assertNotNull(opReturnOutput(both));
        assertPayment(both, 50_000L);
        assertEquals(2, both.getOutputs().size());
        assertUntouchedLocktimeRule(both);
    }

    @Test
    public void dustAlongsideAValidOutputOrOpReturnIsRejected() {
        Map<String, Long> withPayment = new LinkedHashMap<String, Long>();
        withPayment.put(BECH32, 50_000L);
        withPayment.put(LEGACY, 1L);
        assertDust(withPayment);

        Map<String, Long> withOpReturn = new LinkedHashMap<String, Long>();
        withOpReturn.put(bip47OpReturn(), 0L);
        withOpReturn.put(BECH32, SamouraiWalletConst.bDust.longValue() - 1L);
        assertDust(withOpReturn);
    }

    private static void assertDust(Map<String, Long> receivers) {
        MakeTxException error = assertThrows(MakeTxException.class, () -> compose(receivers));
        assertEquals("Dust amount", error.getMessage());
    }

    private static Transaction compose(Map<String, Long> receivers) throws Exception {
        return SendFactoryGeneric.getInstance().makeTransaction(
                receivers, unspent(), BIP, false, PARAMS, BLOCK_HEIGHT);
    }

    private static Map<String, Long> singleton(String address, long sats) {
        Map<String, Long> receivers = new LinkedHashMap<String, Long>();
        receivers.put(address, sats);
        return receivers;
    }

    private static Collection<MyTransactionOutPoint> unspent() throws Exception {
        byte[] script = BIP.getTransactionOutput(BECH32, 100_000L, PARAMS).getScriptBytes();
        MyTransactionOutPoint outPoint = new MyTransactionOutPoint(
                PARAMS,
                Sha256Hash.wrap(TXID),
                0,
                BigInteger.valueOf(100_000L),
                script,
                BECH32,
                1);
        return Collections.singletonList(outPoint);
    }

    private static void assertPayment(Transaction tx, long sats) {
        TransactionOutput payment = null;
        for (TransactionOutput output : tx.getOutputs()) {
            if (!output.getScriptPubKey().isOpReturn() && output.getValue().value == sats) {
                payment = output;
            }
        }
        assertNotNull(payment, "missing " + sats + " sat output");
    }

    private static TransactionOutput opReturnOutput(Transaction tx) {
        for (TransactionOutput output : tx.getOutputs()) {
            if (output.getScriptPubKey().isOpReturn()) {
                return output;
            }
        }
        return null;
    }

    /**
     * The locktime / RBF opt-in in makeTransaction runs only for four receivers.
     * A one- or two-output compose must leave version and locktime alone.
     */
    private static void assertUntouchedLocktimeRule(Transaction tx) {
        assertEquals(1L, tx.getVersion());
        assertEquals(0L, tx.getLockTime());
    }

    private static String bip47OpReturn() {
        StringBuilder payload = new StringBuilder(160);
        payload.append("010002");
        for (int i = 3; i < 80; i++) {
            payload.append(String.format("%02x", i));
        }
        return payload.toString();
    }
}
