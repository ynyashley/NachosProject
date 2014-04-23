package nachos.threads;

import nachos.machine.*;
import java.util.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	private PriorityQueue<TimedThread> waitQueue;
	
	public Alarm() {
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
		waitQueue = new PriorityQueue<TimedThread>();
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		Machine.interrupt().disable();
		while(!waitQueue.isEmpty() && waitQueue.peek().getWaitTime() <= Machine.timer().getTime()) {
			TimedThread first = waitQueue.remove();
			first.getKThread().ready();
		}
		Machine.interrupt().enable();
		KThread.yield();
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		// for now, cheat just to get something working (busy waiting is bad)
		long wakeTime = Machine.timer().getTime() + x;
		Machine.interrupt().disable();
		waitQueue.add(new TimedThread(wakeTime, KThread.currentThread()));
		KThread.sleep();
		Machine.interrupt().enable();
	}
	
	private class TimedThread implements Comparable<TimedThread> {
		protected Long waitTime;
		private KThread kt;
		
		protected TimedThread(long waitTime, KThread kt) {
			setWaitTime(waitTime);
			setKThread(kt);
		}
		
		@Override
		public int compareTo(TimedThread tt) {
			if(getWaitTime() > tt.getWaitTime())
				return 1;
			else if (getWaitTime() == tt.getWaitTime()) {
				return 0;
			}
			else 
				return -1;
		}

		public KThread getKThread() {
			return kt;
		}

		public void setKThread(KThread kt) {
			this.kt = kt;
		}
		
		public long getWaitTime() {
			return this.waitTime;
		}
		
		public void setWaitTime(long waitTime) {
			this.waitTime = waitTime;
		}
	}
}
