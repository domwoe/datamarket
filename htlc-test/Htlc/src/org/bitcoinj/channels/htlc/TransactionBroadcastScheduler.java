package org.bitcoinj.channels.htlc;

import java.util.Date;
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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * This class maintains a set of transactions that need to be broadcasted
 * at some time in the future
 * @author frabu
 *
 */
public class TransactionBroadcastScheduler {
	
	protected final ReentrantLock lock = 
		Threading.lock("TransactionBroadcastScheduler");
	
	@GuardedBy("lock") Map<Transaction, TimerTask> broadcastScheduleMap;
	final Timer timeoutHandler = new Timer(true);
	private TransactionBroadcaster peerGroup;
	
	public TransactionBroadcastScheduler(TransactionBroadcaster peerGroup) {
		this.peerGroup = peerGroup;
		this.broadcastScheduleMap = new HashMap<Transaction, TimerTask>();
	}
	
	public TransactionBroadcaster getBroadcaster() {
		return peerGroup;
	}
	
	/**
	 * Simple wrapper for broadcast future 
	 */
	public ListenableFuture<Transaction> broadcastTransaction(
		final Transaction tx
	) {
		lock.lock();
		try {
			return peerGroup.broadcastTransaction(tx).future();
		} finally {
			lock.unlock();
		}
	}
	
	public void updateSchedule(
		Transaction oldTx, 
		Transaction newTx, 
		long delay
	) {
		removeTransaction(oldTx);
		scheduleTransaction(newTx, delay);
	}
	
	public void scheduleTransaction(final Transaction tx, final long delay) {
		lock.lock();
		try {
			//log.info("Scheduled TX: {} at {}", tx, new Date(delay*1000));
			TimerTask timerTask = new TimerTask() {
				@Override
				public void run() {
					try {
						//log.info("Broadcasting Tx");
						ListenableFuture<Transaction> future = 
							peerGroup.broadcastTransaction(tx).future();
						Futures.addCallback(
							future,
							new FutureCallback<Transaction>() {
								@Override public void onSuccess(
									Transaction transaction
								) {
							//		log.info("TX {} propagated", tx);
								}
								@Override public void onFailure(
									Throwable throwable
								) {
							/*		log.error(
										"Failed to broadcast tx {}", 
										throwable.toString()
									);*/
								}
							}
						);
//						log.info("Removing after broadcast");
						removeTransaction(tx);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			};
			// Store so we can cancel later if needed
			broadcastScheduleMap.put(tx, timerTask);
			timeoutHandler.schedule(
				timerTask,
				new Date(delay*1000)
			);
		} finally {
			lock.unlock();
		}
	}
	
	public void removeTransaction(Transaction tx) {
		lock.lock();
		try {
		//	log.info("Removing Tx: {} from scheduler", tx);
			broadcastScheduleMap.get(tx).cancel();
			broadcastScheduleMap.remove(tx);
		} finally {
			lock.unlock();
		}
	}
}
