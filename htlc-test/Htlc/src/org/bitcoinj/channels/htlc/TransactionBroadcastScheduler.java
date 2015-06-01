package org.bitcoinj.channels.htlc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.concurrent.GuardedBy;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains a set of transactions that need to be broadcasted
 * at some time in the future
 * @author frabu
 *
 */
public class TransactionBroadcastScheduler {
	private static final Logger log = 
		LoggerFactory.getLogger(TransactionBroadcastScheduler.class);
	protected final ReentrantLock lock = 
		Threading.lock("TransactionBroadcastScheduler");
	
	@GuardedBy("lock") Map<Transaction, TimerTask> broadcastScheduleMap;
	final Timer timeoutHandler = new Timer(true);
	private TransactionBroadcaster peerGroup;
	
	public TransactionBroadcastScheduler(TransactionBroadcaster peerGroup) {
		this.peerGroup = peerGroup;
		this.broadcastScheduleMap = new HashMap<Transaction, TimerTask>();
	}
	
	public void scheduleRefund(
		final Transaction contractTx, 
		final Transaction refundTx
	) {
		lock.lock();
		try {
			TimerTask timerTask = new TimerTask() {
				@Override
				public void run() {
					removeTransaction(refundTx);
					peerGroup.broadcastTransaction(contractTx);
					peerGroup.broadcastTransaction(refundTx);
				}
			};
			// Store so we can cancel later
			broadcastScheduleMap.put(refundTx, timerTask);
			timeoutHandler.schedule(
				timerTask,
				refundTx.getLockTime()
			);
		} finally {
			lock.unlock();
		}
	}
	
	private void removeTransaction(Transaction tx) {
		lock.lock();
		try {
			broadcastScheduleMap.get(tx).cancel();
			broadcastScheduleMap.remove(tx);
		} finally {
			lock.unlock();
		}
	}
}
