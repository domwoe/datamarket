package org.bitcoinj.channels.htlc;

import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
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

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class HTLCChannelServerState {
	private static final Logger log = 
		LoggerFactory.getLogger(HTLCChannelServerState.class);
	private final int SERVER_OUT_IDX = 0;
	private final int CLIENT_OUT_IDX = 1;
	/**
	 * Time when to broadcast the teardownTx before the channel refundTx
	 * becomes valid and can be used by the sending counterpart (in minutes
	 * before refundTx timelock)
	 */
	private final int TIME_DELTA = 2;

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

	private ECKey clientPrimaryKey;
	private ECKey clientSecondaryKey;
	private ECKey serverKey;
	
	private final Wallet wallet;
	
	private Transaction multisigContract = null;
	private Script multisigScript;
	
	 // The object that will broadcast transactions for us - usually a peer group.
    private final TransactionBroadcastScheduler broadcaster;
	
	private TransactionSignature teardownClientSig;
	private Transaction teardownTx;
	
	private TransactionOutput clientOutput;
	
	private Coin totalValue; // Total value locked into the multisig output
	private Coin bestValueToMe = Coin.ZERO;
	private Coin feePaidForPayment;
	
	private final long channelExpireTime;
	private final long htlcTeardownExpireTime;
	private final long htlcSettlementExpireTime;
	private final long htlcRefundExpireTime;
	
	Map<String, HTLCServerState> htlcMap; // Map secret's hash to state obj
	
	private final SecureRandom random;
	
	public HTLCChannelServerState(
			Wallet wallet,
			ECKey serverKey,
			long minExpireTime,
			TransactionBroadcastScheduler broadcaster
	) {
		this.state = State.WAITING_FOR_REFUND_TRANSACTION;
		this.serverKey = serverKey;
		this.wallet = wallet;
		this.channelExpireTime = minExpireTime;
		this.htlcTeardownExpireTime = minExpireTime - TIME_DELTA*60;
		this.htlcSettlementExpireTime = minExpireTime + TIME_DELTA*60;
		this.htlcRefundExpireTime = minExpireTime + 2*TIME_DELTA*60;
		this.broadcaster = broadcaster;
		this.random = new SecureRandom();
		this.htlcMap = new HashMap<String, HTLCServerState>();
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
		if (refundTx.getLockTime() < channelExpireTime) {
			throw new VerificationException(
				"Refund tx has a lock time that is too early!"
			);
		}
		if (refundTx.getOutputs().size() != 1) {
			throw new VerificationException(
				"Refund tx does not have exactly one output!"
			);
		}

		// Sign the refund tx
		clientPrimaryKey = ECKey.fromPublicOnly(clientMultisigPubKey);
		Script multisigPubKey = ScriptBuilder.createMultiSigOutputScript(
			2, 
			ImmutableList.of(clientPrimaryKey, serverKey)
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
			Lists.newArrayList(clientPrimaryKey, serverKey)
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
    		broadcaster.broadcastTransaction(multisigContract), 
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
	
	public synchronized void receiveInitialTeardown(
		Transaction newTeardownTx, 
		TransactionSignature teardownSig
	) throws ValueOutOfRangeException {
		checkState(state == State.READY);
		
		Coin refundSize = totalValue.subtract(
			newTeardownTx.getOutput(CLIENT_OUT_IDX).getValue()
		);
		Coin newValueToMe = totalValue.subtract(
			newTeardownTx.getOutput(SERVER_OUT_IDX).getValue()
		);
		
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

		log.info("Teardown hash: {}", newTeardownTx.getHash());
		log.info("Teardown sig: {}", new BigInteger(teardownSig.encodeToBitcoin()));
		log.info("Teardown connected output: {}", multisigScript);
		
		// Let's sign and verify the client's signature
		TransactionSignature mySig = newTeardownTx.calculateSignature(
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
		
		TransactionInput teardownInput = newTeardownTx.getInput(0);
		log.info("Teardown input hash: {}", teardownInput.hashCode());
		teardownInput.setScriptSig(scriptSig);
		teardownInput.verify(multisigContract.getOutput(0));
		
		// Update teardown in the broadcast scheduler
		log.info("TEARDOWN NOW IS: {}", newTeardownTx);
		this.teardownTx = newTeardownTx;
		this.teardownClientSig = teardownSig;
		broadcaster.scheduleTransaction(teardownTx, htlcTeardownExpireTime);
		
	}
	
	private String nextSecret() {
	    return new BigInteger(130, random).toString(32);
	}
	
	/**
	 * First method to be called when initializing a new payment
	 * Creates a new HTLCState object and inserts it into the map
	 */
	public HTLCServerState createNewHTLC(Coin value) {
		String secret = nextSecret();
		
		final String hashedSecret = Hashing.sha256()
	        .hashString(secret, Charsets.UTF_8)
	        .toString();

		HTLCServerState htlcState = new HTLCServerState(
			value, 
			secret,
			hashedSecret, // This is the HTLC id
			htlcSettlementExpireTime, 
			htlcRefundExpireTime
		);
		log.info("Generated secret and hash: {} {}", secret, hashedSecret);
		htlcMap.put(hashedSecret, htlcState);
		return htlcState;
	}
	
	/**
	 * This is called when the server is provided with the updated teardownTx
	 * (with the freshly added HTLC output)
	 * @return The hash of the teardown and the signed HTLC refund Tx
	 */
	public SignedTransactionWithHash getSignedRefundAndTeardownHash(
		String htlcId,
		Transaction teardownTx,
		TransactionSignature clientSig
	) {
		// TODO: add verification that this indeed increases the value
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
		
		// Update the teardown in the broadcast scheduler
		// IMPORTANT: Only use this after signing - else Transaction hash is wrong!
		broadcaster.updateSchedule(
			this.teardownTx, 
			teardownTx, 
			htlcTeardownExpireTime
		);
		
		this.teardownTx = teardownTx;
		this.teardownClientSig = clientSig;
		
		log.info("RECEIVED UPDATED TEARDOWN TX {}", this.teardownTx);
		
		HTLCServerState htlcState = htlcMap.get(htlcId);
		SignedTransactionWithHash signedRefundTx = 
			htlcState.getSignedRefund(teardownTx, clientPrimaryKey, serverKey);
		// Update the map
		htlcMap.put(htlcId, htlcState);
		
		return signedRefundTx;
	}
	
	public synchronized void setClientSecondaryKey(ECKey clientSecondaryKey) {
		this.clientSecondaryKey = clientSecondaryKey;
	}
	
	public synchronized void finalizeHTLCSettlementTx(
		String htlcId,
		Transaction settleTx,
		TransactionSignature settleSig
	) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		htlcState.verifyAndStoreSignedSettlementTx(
			clientSecondaryKey,
			teardownTx.getOutput(2).getScriptPubKey(),
			settleTx,
			settleSig
		);
		// Update the map
		htlcMap.put(htlcId, htlcState);
	}
	
	public synchronized void finalizeHTLCForfeitTx(
		String htlcId,
		Transaction forfeitTx,
		TransactionSignature forfeitSig
	) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		htlcState.verifyAndStoreSignedForfeitTx(
			clientPrimaryKey,
			teardownTx.getOutput(2).getScriptPubKey(),
			forfeitTx, 
			forfeitSig
		);
		// Update the map
		htlcMap.put(htlcId, htlcState);
	}
	
	public SignedTransaction getFullForfeitTx(String htlcId) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		htlcMap.put(htlcId, htlcState);
		return htlcState.getFullForfeitTx(teardownTx, serverKey);
	}
	
	public synchronized String getSecretForHTLC(String htlcId) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		return htlcState.getSecret();
	}
	
	/**
	 * Method that gets the fully signed settlementTX in case the timelock
	 * expired and the server can pull its money due to non-collaborative client
	 */
	public Transaction getFullSettlementTx(String htlcId) {
		HTLCServerState htlcState = htlcMap.get(htlcId);
		Transaction settlementTx = 
			htlcState.getFullSettlementTx(teardownTx, serverKey);
		htlcMap.put(htlcId, htlcState);
		// Let's schedule now the settlement for broadcast, then cancel it later
		// if we settle this HTLC
		broadcaster.scheduleTransaction(settlementTx, htlcSettlementExpireTime);
		
		return settlementTx;
	}
	
	/**
	 * Call this when server was able to provide secret in time to the sender
	 * and the client sends an updated teardownTx
	 */
	public boolean removeHTLCAndUpdateTeardownTx(
		String htlcId,
		Transaction newTeardownTx,
		TransactionSignature teardownSig
	) {
		// TODO: Add HTLCState validation and check the new teardown values
		HTLCServerState htlcState = htlcMap.get(htlcId);
		htlcMap.remove(htlcId);
		log.info("ATTEMPTING TO UPDATE BROADCAST FOR TEARDOWN {}", teardownTx);
		broadcaster.updateSchedule(
			teardownTx,
			newTeardownTx, 
			htlcTeardownExpireTime
		);
		// TODO: Cancel the settlement broadcast/ Add proper scheduling with full settlement
		// transaction
		// broadcaster.removeTransaction(htlcState.getSettlementTx());
		this.teardownTx = newTeardownTx;
		this.teardownClientSig = teardownSig;
		htlcMap.remove(htlcId);
		return true;
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
		Wallet.SendRequest req = Wallet.SendRequest.forTx(teardownTx);
		Transaction tx = req.tx;
		// Sign the multisig input
		TransactionSignature mySig = tx.calculateSignature(
			0, serverKey, multisigScript, SigHash.ALL, false
		);
		TransactionSignature bestValueSig = 
			TransactionSignature.decodeFromBitcoin(
				teardownClientSig.encodeToBitcoin(), 
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
			broadcaster.broadcastTransaction(tx);
		
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