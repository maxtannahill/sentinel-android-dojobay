package com.samourai.sentinel.send;

import com.samourai.sentinel.core.SentinelState;
import com.samourai.sentinel.sweep.MyTransactionInput;
import com.samourai.sentinel.util.FormatsUtil;
import com.samourai.wallet.SamouraiWalletConst;
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric;
import com.samourai.wallet.send.MyTransactionOutPoint;
import com.samourai.wallet.util.FormatsUtilGeneric;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class SendFactory	{

    private static SendFactory instance = null;

    private SendFactory () { ; }

    public static SendFactory getInstance() {


        if(instance == null)	{
            instance = new SendFactory();
        }

        return instance;
    }

    public Transaction makeTransaction(final int accountIdx, final List<MyTransactionOutPoint> unspent, final HashMap<String, BigInteger> receivers) throws Exception {
        return makeTransaction(accountIdx, receivers, unspent);
    }

    /*
    Used by spends
     */
    private Transaction makeTransaction(int accountIdx, HashMap<String, BigInteger> receivers, List<MyTransactionOutPoint> unspent) throws Exception {

        BigInteger amount = BigInteger.ZERO;
        for (Map.Entry<String, BigInteger> mapEntry : receivers.entrySet()) {
            amount = amount.add(mapEntry.getValue());
        }

        List<TransactionOutput> outputs = new ArrayList<TransactionOutput>();
        Transaction tx = new Transaction(SentinelState.Companion.getNetworkParam());

        for (Map.Entry<String, BigInteger> mapEntry : receivers.entrySet()) {
            String toAddress = mapEntry.getKey();
            BigInteger value = mapEntry.getValue();
            boolean bip47OpReturn = FormatsUtilGeneric.getInstance().isValidBIP47OpReturn(toAddress);
            if (value == null || (value.compareTo(BigInteger.ZERO) <= 0 && !bip47OpReturn)) {
                throw new Exception("Invalid amount");
            }
            // #463: same threshold change selection uses. Outputs below bDust are
            // non-standard; the threshold itself is payable. BIP47 OP_RETURN is unspendable.
            if (value.compareTo(SamouraiWalletConst.bDust) < 0 && !bip47OpReturn) {
                throw new Exception("Dust amount");
            }

            TransactionOutput output = null;
            Script toOutputScript = null;
            if (!FormatsUtil.Companion.isValidBitcoinAddress(toAddress) && bip47OpReturn) {
                toOutputScript = new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(Hex.decode(toAddress)).build();
                output = new TransactionOutput(SentinelState.Companion.getNetworkParam(), null, Coin.valueOf(0L), toOutputScript.getProgram());
            } else if (FormatsUtil.Companion.isValidBech32(toAddress)) {
                output = Bech32UtilGeneric.getInstance().getTransactionOutput(toAddress, value.longValue(), SentinelState.Companion.getNetworkParam());
            } else {
                toOutputScript = ScriptBuilder.createOutputScript(org.bitcoinj.core.Address.fromBase58(SentinelState.Companion.getNetworkParam(), toAddress));
                output = new TransactionOutput(SentinelState.Companion.getNetworkParam(), null, Coin.valueOf(value.longValue()), toOutputScript.getProgram());
            }

            outputs.add(output);
        }

        List<MyTransactionInput> inputs = new ArrayList<MyTransactionInput>();
        for(MyTransactionOutPoint outPoint : unspent) {
            Script script = new Script(outPoint.getScriptBytes());

            if(script.getScriptType() == Script.ScriptType.NO_TYPE) {
                continue;
            }

            MyTransactionInput input = new MyTransactionInput(SentinelState.Companion.getNetworkParam(), null, new byte[0], outPoint, outPoint.getTxHash().toString(), outPoint.getTxOutputN());
//            if(PrefsUtil.getInstance(context).getValue(PrefsUtil.RBF_OPT_IN, false) == true)    {
//                input.setSequenceNumber(SamouraiWallet.RBF_SEQUENCE_VAL.longValue());
//            }
            inputs.add(input);
        }

        //
        // deterministically sort inputs and outputs, see BIP69 (OBPP)
        //
//        Collections.sort(inputs, new BIP69InputComparator());
        for(TransactionInput input : inputs) {
            tx.addInput(input);
        }

        Collections.sort(outputs, new com.samourai.sentinel.sweep.SendFactory.BIP69OutputComparator());
        for(TransactionOutput to : outputs) {
            tx.addOutput(to);
        }

        return tx;
    }

}
