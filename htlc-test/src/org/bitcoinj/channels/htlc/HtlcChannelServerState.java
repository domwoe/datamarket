package org.bitcoinj.channels.htlc;

import java.util.Arrays;
import java.util.Date;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class HtlcChannelServerState {
	private static final Logger log = 
		LoggerFactory.getLogger(HtlcChannelServerState.class);
	private static final NetworkParameters PARAMS = RegTestParams.get();

	public enum State {
		WAITING_FOR_REFUND_TRANSACTION,
		WAITING_FOR_MULTISIG_CONTRACT,
		WAITING_FOR_MULTISIG_ACCEPTANCE,
		READY,
		WAITING_FOR_HTLC,
		RECEIVED_HTLC,
		SETTLED_HTLC,
		EXPIRED_HTLC,
		CLOSING,
		CLOSED,
		ERROR,
	}
	private State state;

	private ECKey clientKey;
	private ECKey serverKey;
	
	private final Wallet wallet;
	
	private Transaction multisigContract = null;
	private Transaction htlcTx;
	private Script multisigScript;
	
	 // The object that will broadcast transactions for us - usually a peer group.
    private final TransactionBroadcaster broadcaster;
	
	private byte[] bestValueSignature;
	private Transaction bestValueTx;
	
	private Coin totalValue; // Total value locked into the multisig output
	private Coin bestValueToMe = Coin.ZERO;
	private Coin feePaidForPayment;
	
	private TransactionOutput clientOutput;
	private long refundTxUnlockTimeSecs;
	
	private static final long DAILY_MINUTES = 1440;
	
	private final long minExpireTime;
	private final long htlcSettlementExpireTime;
	private final long htlcRefundExpireTime;
	
	/** Container for a signature and an assurance tx. */
    public static class SignedTransaction {
    	public Transaction tx;
        public TransactionSignature signature;
    }
	
	public HtlcChannelServerState(
			Wallet wallet,
			ECKey serverKey,
			long minExpireTime,
			TransactionBroadcaster broadcaster
	) {
		this.state = State.WAITING_FOR_REFUND_TRANSACTION;
		this.serverKey = serverKey;
		this.wallet = wallet;
		this.minExpireTime = minExpireTime;
		this.htlcSettlementExpireTime = 
			new Date().getTime()/1000l + (DAILY_MINUTES + minExpireTime)*60;
		this.htlcRefundExpireTime = 
			new Date().getTime()/1000l + (2*DAILY_MINUTES + minExpireTime)*60;
		this.broadcaster = broadcaster;
	}
	
	/**
	 * Server was provided with the timelocked client refund tx to sign, so
	 * check the received tx and then sign it 
	 * @param refundTx
	 * @param clientMultisigPubKey
	 * @return the refund tx signature 
	 * @throws VerificationException
	 */
	public synchronized byte[] provideRefundTransaction(
			Transaction refundTx,
			byte[] clientMultisigPubKey
	) throws VerificationException {
		log.info("Provided with refund transaction: {}", refundTx);
		// Do basic sanity check
		refundTx.verify();
		// Verify that the refundTx has a single input
		if (refundTx.getInputs().size() != 1) {
			throw new VerificationException(
				"Refund transaction does not have exactly one input!"
			);
		}
		// Verify that the refundTx has a timelock and a sequence number of zero
		if (refundTx.getInput(0).getSequenceNumber() != 0) {
			throw new VerificationException(
				"Refund tx input's sequence number is non-zero!"
			);
		}
		if (refundTx.getLockTime() < minExpireTime) {
			throw new VerificationException(
				"Refund tx has a lock time that is too early!"
			);
		}
		if (refundTx.getOutputs().size() != 2) {
			throw new VerificationException(
				"Refund tx does not have exactly two outputs!"
			);
		}
		refundTxUnlockTimeSecs = refundTx.getLockTime();
		// Sign the refund tx
		clientKey = ECKey.fromPublicOnly(clientMultisigPubKey);
		Script multisigPubKey = ScriptBuilder.createMultiSigOutputScript(
			2, 
			ImmutableList.of(clientKey, serverKey)
		);
		TransactionSignature sig = refundTx.calculateSignature(
			0, 
			serverKey, 
			multisigPubKey, 
			Transaction.SigHash.ALL, 
			false
		);
		log.info("Signed refund transaction.");
		this.clientOutput = refundTx.getOutput(0);
		state = State.WAITING_FOR_MULTISIG_CONTRACT;
		return sig.encodeToBitcoin();
	}
	
	/**
	 * Received the contract, broadcast it to lock in the client money
	 * @param multisigContract
	 * @return
	 */
	public synchronized ListenableFuture<HtlcChannelServerState> 
		provideMultisigContract(
			final Transaction multisigContract
	) {
		multisigContract.verify();
		this.multisigContract = multisigContract;
		this.multisigScript = multisigContract.getOutput(0).getScriptPubKey();
		
		final Script expectedScript = ScriptBuilder.createMultiSigOutputScript(
			2, 
			Lists.newArrayList(clientKey, serverKey)
		);
		if (!Arrays.equals(
				multisigScript.getProgram(), 
				expectedScript.getProgram())
			) {
            throw new VerificationException(
        		"Multisig contract's first output was not a standard " +
        		"2-of-2 multisig to client and server in that order."
    		);
		}
		
		this.totalValue = multisigContract.getOutput(0).getValue();
		if (this.totalValue.signum() <= 0) {
			throw new VerificationException(
				"Not accepting an attempt to open a contract with zero value."
			);
		}
		log.info("Broadcasting multisig contract: {}", multisigContract);
		state = State.WAITING_FOR_MULTISIG_ACCEPTANCE;
		final SettableFuture<HtlcChannelServerState> future = 
			SettableFuture.create();
        Futures.addCallback(
    		broadcaster.broadcastTransaction(
				multisigContract).future(), 
				new FutureCallback<Transaction>() 
			{
    			@Override public void onSuccess(Transaction transaction) {
    				log.info("Successfully broadcast multisig contract {}. " +
    						"Channel now open.", transaction.getHashAsString());
    				state = State.READY;
    				future.set(HtlcChannelServerState.this);
    			}
    			@Override public void onFailure(Throwable throwable) {
    				// Couldn't broadcast the transaction for some reason.
		            log.error(throwable.toString());
		            throwable.printStackTrace();
		            state = State.ERROR;
		            future.setException(throwable);
		        }
			}
		);
        return future;
	}
	
	public byte[] provideHtlcRefundTransaction(
		Transaction htlcRefundTx,
		byte[] clientMultisigPubKey,
		Coin value
	) {
		log.info("Provided with refund transaction: {}", htlcRefundTx);
		// Do basic sanity check
		htlcRefundTx.verify();
		// Verify that the refundTx has a single input
		if (htlcRefundTx.getInputs().size() != 1) {
			throw new VerificationException(
				"HTLC refund transaction does not have exactly one input!"
			);
		}
		// Verify that the refundTx has a proper time-lock
		if (htlcRefundTx.getLockTime() < htlcRefundExpireTime) {
			throw new VerificationException(
				"HTLC refund tx has a lock time that is too early!"
			);
		}
		if (htlcRefundTx.getOutputs().size() != 1) {
			throw new VerificationException(
				"Htlc refund tx does not have exactly one output!"
			);
		}
		if (htlcRefundTx.getOutput(0).getValue().compareTo(value) != 0) {
			throw new VerificationException(
				"Htlc refund tx has an invalid value locked in!"
			);
		}
		// Sign the HTLC refund tx
		clientKey = ECKey.fromPublicOnly(clientMultisigPubKey);
		Script multisigPubKey = ScriptBuilder.createMultiSigOutputScript(
			2, 
			ImmutableList.of(clientKey, serverKey)
		);
		TransactionSignature sig = htlcRefundTx.calculateSignature(
			0, 
			serverKey, 
			multisigPubKey, 
			Transaction.SigHash.ALL, 
			false
		);
		log.info("Signed HTLC refund transaction.");
		state = State.WAITING_FOR_HTLC;
		return sig.encodeToBitcoin();
	}
	
	public synchronized void setHtlc(Transaction htlcTx) {
		this.htlcTx = htlcTx;
		this.state = State.RECEIVED_HTLC;
	}
	
	public Transaction createHtlcSettlementTx(String secret) {
		
		Transaction settlementTx = new Transaction(PARAMS);
		// Lock it for 9 minutes
		int minutes = 9;
		long lockTime = new Date().getTime() / 1000l + minutes*60;
		settlementTx.setLockTime(lockTime);

		settlementTx.addOutput(Coin.valueOf(1, 0), clientKey.toAddress(PARAMS));
		TransactionOutput htlcOut = htlcTx.getOutput(0);
		TransactionInput settleInput = 
			settlementTx.addInput(htlcTx.getOutput(0));

		Sha256Hash sigHash = settlementTx.hashForSignature(
			0, 
			htlcOut.getScriptPubKey(), 
			Transaction.SigHash.ALL, 
			false
		);
		ECKey.ECDSASignature sigB = clientKey.sign(sigHash);
		TransactionSignature sigTS = 
			new TransactionSignature(sigB, Transaction.SigHash.ALL, false);

		// Create the script that spends the multi-sig output.
		ScriptBuilder bld = new ScriptBuilder();
		bld.data(sigTS.encodeToBitcoin());
		bld.data(secret.getBytes());
		bld.data(new byte[]{});
		Script inputScript = bld.build();

		settleInput.setScriptSig(inputScript);
		settleInput.verify(htlcOut);

		return settlementTx;
	}
	
	/**
	 * Creates a transaction from the output of the old contract to reassure
	 * that it will not reveal the secret at a later time after the contract
	 * has been updated and steal the coins locked in the HTLC
	 */
	public SignedTransaction createAssuranceTx() {
		Transaction tx = new Transaction(PARAMS);
		tx.addInput(htlcTx.getOutput(0));
		tx.addOutput(
			htlcTx.getOutput(0).getValue(), 
			clientKey.toAddress(PARAMS)
		);
		TransactionSignature mySignature = tx.calculateSignature(
			0,
			serverKey,
			multisigScript,
			Transaction.SigHash.ALL,
			false
		);
		SignedTransaction sigTx = new SignedTransaction();
		sigTx.tx = tx;
		sigTx.signature = mySignature;
		return sigTx;
	}
	
	public synchronized boolean incrementPayment(
		Transaction refundTx,
		Coin refundSize, 
		byte[] signatureBytes
	) throws ValueOutOfRangeException {
		TransactionSignature sig = 
			TransactionSignature.decodeFromBitcoin(signatureBytes, true);
		final boolean fullyUsedUp = refundSize.equals(Coin.ZERO);
		if (
			refundSize.compareTo(clientOutput.getMinNonDustValue()) < 0 && 
			!fullyUsedUp
		) {
			throw new ValueOutOfRangeException(
				"Attempt to refund negative value or " +
				"value to small to be accepted by the network!"
			);
		}
		Coin newValueToMe = totalValue.subtract(refundSize);
		if (newValueToMe.signum() < 0) {
			throw new ValueOutOfRangeException(
				"Attempt to refund more than the contract allows"
			);
		}
		if (newValueToMe.compareTo(bestValueToMe) < 0) {
			throw new ValueOutOfRangeException(
				"Attempt to roll back payment on the channel"
			);
		}
		Coin contractValueToClient = refundTx.getOutput(0).getValue();
		Coin contractValueToMe = refundTx.getOutput(1).getValue();
		if (newValueToMe.compareTo(contractValueToMe) != 0 ||
			refundSize.compareTo(contractValueToClient) != 0) {
			throw new VerificationException(
				"Invalid transaction output values " + refundTx
			);
		}	
		if (sig.sigHashMode() != Transaction.SigHash.ALL || 
			sig.anyoneCanPay()
		) {
			throw new VerificationException(
				"New payment signature was not signed with " +
				"the right SIGHASH flags"
			);
		}
		Sha256Hash sighash = refundTx.hashForSignature(
			0, 
			multisigScript, 
			Transaction.SigHash.ALL, 
			false
		);
		if (!clientKey.verify(sighash, sig)) {
			throw new VerificationException(
				"Signature does not verify on tx " + refundTx
			);
		}
		bestValueToMe = newValueToMe;
		bestValueSignature = signatureBytes;
		bestValueTx = refundTx;
		return !fullyUsedUp;
	}
	
	final SettableFuture<Transaction> closedFuture = SettableFuture.create();
	
	/**
	 * Close the channel and broadcast the highest value payment
	 * @throws InsufficientMoneyException 
	 */
	public synchronized ListenableFuture<Transaction> close() 
		throws InsufficientMoneyException 
	{
		if (state != State.READY) {
			log.warn(
				"Attempt to close channel in invalid state " + state.toString()
			);
			return closedFuture;
		}
		Wallet.SendRequest req = Wallet.SendRequest.forTx(bestValueTx);
		Transaction tx = req.tx;
		// Sign the multisig input
		TransactionSignature mySig = tx.calculateSignature(
			0, serverKey, multisigScript, SigHash.ALL, false
		);
		TransactionSignature bestValueSig = 
			TransactionSignature.decodeFromBitcoin(bestValueSignature, true);
		Script scriptSig =
			ScriptBuilder.createMultiSigInputScript(bestValueSig, mySig);
		tx.getInput(0).setScriptSig(scriptSig);
		tx.verify();
		req.shuffleOutputs = false;
		wallet.completeTx(req);
		feePaidForPayment = req.tx.getFee();
		log.info("Calculated fee is {}", feePaidForPayment);
		if (feePaidForPayment.compareTo(bestValueToMe) >= 0) {
		     final String msg = String.format("" +
	     		"Had to pay more in fees (%s) than the channel was worth (%s)",
	            feePaidForPayment, bestValueToMe
    		 );
		     throw new InsufficientMoneyException(
	    		 feePaidForPayment.subtract(bestValueToMe), 
	    		 msg
    		 );
		}
		state = State.CLOSING;
		log.info("Closing channel, broadcasting tx {}", tx);
		ListenableFuture<Transaction> future = broadcaster.broadcastTransaction(tx).future();
	    	Futures.addCallback(future, new FutureCallback<Transaction>() {
	    		@Override public void onSuccess(Transaction transaction) {
	                log.info("TX {} propagated, channel successfully closed.", transaction.getHash());
	                state = State.CLOSED;
	                closedFuture.set(transaction);
	            }

	            @Override public void onFailure(Throwable throwable) {
	                log.error("Failed to settle channel, could not broadcast: {}", throwable.toString());
	                throwable.printStackTrace();
	                state = State.ERROR;
	                closedFuture.setException(throwable);
	            }
	        }
    	);
	    return closedFuture;
	}
	
	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}
}
