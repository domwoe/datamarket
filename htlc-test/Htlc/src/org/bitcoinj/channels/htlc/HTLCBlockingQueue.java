package org.bitcoinj.channels.htlc;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class HTLCBlockingQueue<T> {
	
	private Queue<T> q = new LinkedList<T>();
	
	private final Condition isFullCondition;
	private final Condition isEmptyCondition;
	private final Lock lock;
	
	private final int limit;
	
	public HTLCBlockingQueue(int limit) {
		this.limit = limit;
		this.lock = new ReentrantLock();
		this.isFullCondition = lock.newCondition();
		this.isEmptyCondition = lock.newCondition();
	}
	
	public void put(T msg) {
		lock.lock();
		try {
			while (isFull()) {
				try {
					isFullCondition.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			q.add(msg);
			isEmptyCondition.signalAll();
		} finally {
			lock.unlock();
		}
	}
	
	public T get() {
		T msg = null;
		lock.lock();
		try {
			while (isEmpty()) {
				try {
					isEmptyCondition.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				msg = q.poll();
				isFullCondition.signalAll();
			}
		} finally {
			lock.unlock();
		}
		return msg;
	}
	
	public List<T> getAll() {
		List<T> elements = new LinkedList<T>();
		lock.lock();
		try {
			while (!q.isEmpty()) {
				T msg = q.poll();
				elements.add(msg);
			}
			/**
			 * Signal that we can add new elements to the queue since it's empty
			 * and we can accumulate the next batch
			 */
			isFullCondition.signalAll();
		} finally {
			lock.unlock();
		}
		return elements;
	}
	
	public boolean isEmpty() {
		return q.size() == 0;
	}
	
	private boolean isFull() {
		return q.size() == limit;
	} 
	
	public int size() {
		return q.size();
	}
}
