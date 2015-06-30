package org.bitcoinj.channels.htlc;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.bitcoin.paymentchannel.Protos;

public class HTLCBlockingQueue {
	
	private Queue<Protos.TwoWayChannelMessage> q = 
		new LinkedList<Protos.TwoWayChannelMessage>();
	
	private final Condition isFullCondition;
	private final Condition isEmptyCondition;
	private final Lock lock;
	
	private final int limit;
	private final int htlcLimit;
	private int htlcs;
	
	public HTLCBlockingQueue(int limit, int maxHTLCs) {
		this.limit = limit;
		this.htlcLimit = maxHTLCs;
		this.lock = new ReentrantLock();
		this.isFullCondition = lock.newCondition();
		this.isEmptyCondition = lock.newCondition();
	}
	
	public void put(Protos.TwoWayChannelMessage msg) {
		lock.lock();
		try {
			while (isFull() || isHTLCFull()) {
				try {
					isFullCondition.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			if (isInitMsg(msg)) {
				htlcs++;
			}
			q.add(msg);
			isEmptyCondition.signalAll();
		} finally {
			lock.unlock();
		}
	}
	
	public Protos.TwoWayChannelMessage get() {
		Protos.TwoWayChannelMessage msg = null;
		lock.lock();
		try {
			while (isEmpty()) {
				try {
					isEmptyCondition.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				msg = q.poll();
				if (isInitMsg(msg)) {
					htlcs--;
				}
				isFullCondition.signalAll();
			}
		} finally {
			lock.unlock();
		}
		return msg;
	}
	
	public List<Protos.TwoWayChannelMessage> getAll() {
		List<Protos.TwoWayChannelMessage> elements = new LinkedList<>();
		lock.lock();
		try {
			while (!q.isEmpty()) {
				Protos.TwoWayChannelMessage msg = q.poll();
				elements.add(msg);
			}
			/**
			 * Signal that we can add new elements to the queue since it's empty
			 * and we can accumulate the next batch
			 */
			htlcs = 0;
			isFullCondition.signalAll();
		} finally {
			lock.unlock();
		}
		return elements;
	}
	
	private boolean isInitMsg(Protos.TwoWayChannelMessage msg) {
		return 
			msg.getType() == Protos.TwoWayChannelMessage.MessageType.HTLC_INIT;
	}
	
	private boolean isEmpty() {
		return q.size() == 0;
	}
	
	private boolean isFull() {
		return q.size() == limit;
	}
	
	private boolean isHTLCFull() {
		return htlcs == htlcLimit;
	}
}
