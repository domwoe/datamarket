package org.bitcoinj.channels.htlc;

import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.TransactionSignature;
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
import com.google.protobuf.ByteString;

public class HTLCChannelServerState {
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCChannelServerState.class);
	private final Integer SECRET_SIZE = 64; // Number of random bytes in secret
	private final int SERVER_OUT_IDX = 0;
	private final int CLIENT_OUT_IDX = 1;

	public enum State {
		WAITING_FOR_REFUND_TRANSACTION,
		WAITING_FOR_MULTISIG_CONTRACT,
		WAITING_FOR_MULTISIG_ACCEPTANCE,
		READY,
		CLOSING,
		CLOSED,
		ERROR,
	}
	private State state;

	private ECKey clientKey;
	private ECKey serverKey;
	
	private final Wallet wallet;
	
	private Transaction multisigContract = null;
	private Script multisigScript;
	
	 // The object that will broadcast transactions for us - usually a peer group.
    private final TransactionBroadcaster broadcaster;
	
	private TransactionSignature bestValueSignature;
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
	
	Map<ByteString, HTLCServerState> htlcMap; // Map secret's hash to state obj
	
	private final SecureRandom random;
	
	public HTLCChannelServerState(
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
		this.random = new SecureRandom();
		this.htlcMap = new HashMap<ByteString, HTLCServerState>();
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
		checkState(state == State.WAITING_FOR_REFUND_TRANSACTION);
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
		if (refundTx.getOutputs().size() != 1) {
			throw new VerificationException(
				"Refund tx does not have exactly one output!"
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
		state = State.WAITING_FOR_MULTISIG_CONTRACT;
		return sig.encodeToBitcoin();
	}
	
	/**
	 * Received the contract, broadcast it to lock in the client money
	 * @param multisigContract
	 * @return
	 */
	public synchronized ListenableFuture<HTLCChannelServerState> 
		provideMultisigContract(
			final Transaction multisigContract
	) {
		checkState(state == State.WAITING_FOR_MULTISIG_CONTRACT);
		
		multisigContract.verify();
		this.multisigContract = multisigContract;
		this.clientOutput = multisigContract.getOutput(0);
		this.multisigScript = clientOutput.getScriptPubKey();
		
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
		final SettableFuture<HTLCChannelServerState> future = 
			SettableFuture.create();
        Futures.addCallback(
    		broadcaster.broadcastTransaction(multisigContract).future(), 
			new FutureCallback<Transaction>() {
    			@Override public void onSuccess(Transaction transaction) {
    				log.info("Successfully broadcast multisig contract {}. " +
    						"Channel now open.", transaction.getHashAsString());
    				state = State.READY;
    				future.set(HTLCChannelServerState.this);
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
	
	public synchronized void receiveUpdatedTeardown(
		Transaction teardownTx, 
		TransactionSignature teardownSig
	) throws ValueOutOfRangeException {
		checkState(state == State.READY);
		
		Coin refundSize = 
			totalValue.subtract(teardownTx.getOutput(CLIENT_OUT_IDX).getValue());
		Coin newValueToMe = 
			totalValue.subtract(teardownTx.getOutput(SERVER_OUT_IDX).getValue());
		
		log.info("Received new teardown with valueToMe: {}", newValueToMe.toFriendlyString());
		
		if (refundSize.compareTo(clientOutput.getMinNonDustValue()) < 0) {
			throw new ValueOutOfRangeException(
				"Attempt to refund negative value or value too small to be " +
				"accepted by the network"
			);
		}
		if (newValueToMe.signum() < 0) {
			throw new ValueOutOfRangeException(
				"Attempt to refund more than the contract allows."
			);
		}
		if (newValueToMe.compareTo(bestValueToMe) < 0) {
            throw new ValueOutOfRangeException(
        		"Attempt to roll back payment on the channel."
    		);
		}
		Transaction.SigHash mode = Transaction.SigHash.ALL;
		if (teardownSig.sigHashMode() != mode) {
			throw new VerificationException(
				"New payment signature was not signed with " +
				"the right SIGHASH flags."
			);
		}

		log.info("Teardown hash: {}", teardownTx.getHash());
		log.info("Teardown sig: {}", new BigInteger(teardownSig.encodeToBitcoin()));
		log.info("Teardown connected output: {}", multisigScript);
		
		// Let's sign and verify the client's signature
		TransactionSignature mySig = teardownTx.calculateSignature(
			0, 
			serverKey, 
			multisigScript, 
			mode, 
			false
		);
		
		Script scriptSig = ScriptBuilder.createMultiSigInputScript(
			teardownSig,
			mySig
		);
		
		TransactionInput teardownInput = teardownTx.getInput(0);
		log.info("Teardown input hash: {}", teardownInput.hashCode());
		teardownInput.setScriptSig(scriptSig);
		teardownInput.verify(multisigContract.getOutput(0));
		
		this.bestValueTx = teardownTx;
		this.bestValueSignature = teardownSig;
		
	}
	
	private byte[] nextSecret() {
		byte[] secret = new byte[SECRET_SIZE];
		random.nextBytes(secret);
		return secret;
	}
	
	/**
	 * First method to be called when initializing a new payment
	 * Creates a new HTLCState object and inserts it into the map
	 */
	public HTLCServerState createNewHTLC(Coin value) {
		byte[] secret = nextSecret();
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		md.update(secret);
		byte[] secretHash = md.digest();
		ByteString secretHashBString = ByteString.copyFrom(secretHash);
		HTLCServerState htlcState = new HTLCServerState(
			value, 
			ByteString.copyFrom(secret),
			secretHashBString, // This is the HTLC id
			htlcSettlementExpireTime, 
			htlcRefundExpireTime
		);
		htlcMap.put(secretHashBString, htlcState);
		return htlcState;
	}
	
	/**
	 * This is called when the server is provided with the updated teardownTx
	 * (with the freshly added HTLC output)
	 * @return The hash of the teardown and the signed HTLC refund Tx
	 */
	public SignedTransactionWithHash getSignedRefundAndTeardownHash(
		ByteString htlcId,
		Transaction teardownTx,
		TransactionSignature clientSig
	) {
		// TODO: add verification that this indeed increases the value
		this.bestValueTx = teardownTx;
		this.bestValueSignature = clientSig;
		TransactionSignature serverTeardownSig = teardownTx.calculateSignature(
			0,
			serverKey,
			multisigScript,
			Transaction.SigHash.ALL,
			false
		);
		// Now create an input script to fully sign the teardownTx and verify it
		Script teardownScriptSig = ScriptBuilder.createMultiSigInputScript(
			clientSig,
			serverTeardownSig
		);
		TransactionInput teardownTxIn = teardownTx.getInput(0);
		teardownTxIn.setScriptSig(teardownScriptSig);
		teardownTxIn.verify(multisigContract.getOutput(0));
		
		HTLCServerState htlcState = htlcMap.get(htlcId);
		SignedTransaction signedRefundTx = 
			htlcState.getSignedRefund(teardownTx, clientKey, serverKey);
		// Update the map
		htlcMap.put(htlcId, htlcState);
		
		return new SignedTransactionWithHash(
			signedRefundTx.getTx(),
			signedRefundTx.getSig(),
			teardownTx.getHash()
		);
	}
	
	public synchronized void storeHTLCSettlementTx(
		ByteString htlcId,
		Transaction settleTx,
		TransactionSignature settleSig
	) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		htlcState.storeSignedSettlementTx(
			bestValueTx, 
			settleSig
		);
		// Update the map
		htlcMap.put(htlcId, htlcState);
	}
	
	public synchronized void storeHTLCForfeitTx(
		ByteString htlcId,
		Transaction forfeitTx,
		TransactionSignature forfeitSig
	) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		htlcState.storeSignedForfeitTx(forfeitTx, forfeitSig);
		// Update the map
		htlcMap.put(htlcId, htlcState);
	}
	
	/**
	 * Method that gets the fully signed settlementTX in case the timelock
	 * expired and the server can pull its money due to non-collaborative client
	 */
	public Transaction getFullSettlementTx(ByteString htlcId, String secret) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		Transaction settlementTx = 
			htlcState.getFullSettlementTx(bestValueTx, serverKey, secret);
		htlcMap.put(htlcId, htlcState);
		return settlementTx;
	}
	
	/**
	 * Call this when server was able to provide secret in time to the sender
	 * and the client sends an updated teardownTx
	 */
	public void removeHTLCAndUpdateTeardownTx(
		ByteString htlcId,
		SignedTransaction signedTeardownTx
	) {
		// TODO: Add HTLCState validation and check the new teardown values
		htlcMap.remove(htlcId);
		this.bestValueTx = signedTeardownTx.getTx();
		this.bestValueSignature = signedTeardownTx.getSig();
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
			TransactionSignature.decodeFromBitcoin(
				bestValueSignature.encodeToBitcoin(), 
				true
			);
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
		ListenableFuture<Transaction> future = 
			broadcaster.broadcastTransaction(tx).future();
		
    	Futures.addCallback(future, new FutureCallback<Transaction>() {
    		@Override public void onSuccess(Transaction transaction) {
                log.info(
            		"TX {} propagated, channel successfully closed.", 
            		transaction.getHash()
        		);
                state = State.CLOSED;
                closedFuture.set(transaction);
            }

            @Override public void onFailure(Throwable throwable) {
                log.error(
            		"Failed to settle channel, could not broadcast: {}", 
            		throwable.toString()
        		);
                throwable.printStackTrace();
                state = State.ERROR;
                closedFuture.setException(throwable);
            }
        });
	    return closedFuture;
	}
	
	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}
	
	public Coin getBestValueToMe() {
		return bestValueToMe;
	}
}