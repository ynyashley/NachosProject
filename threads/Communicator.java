package nachos.threads;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	/**
	 * Allocate a new communicator.
	 */
	private Condition2 speaker;
	private Condition2 listener;
	private Condition2 transferer;
	
	private boolean transferred;
	
	private Lock lock;
	private int word;
	
	public Communicator() {
		this.lock = new Lock();
		this.speaker = new Condition2(this.lock);
		this.listener = new Condition2(this.lock);
		this.transferer = new Condition2(this.lock);
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word the integer to transfer.
	 */
	public void speak(int word) {
		lock.acquire();
		
		while(transferred) {
			speaker.sleep();
		}
		this.word = word;
		transferred = true;
		listener.wake();
		transferer.sleep();
		lock.release();
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		int message;
		lock.acquire();
		while(!transferred) {
			speaker.wake();
			listener.sleep();
		}
		message = this.word;
		transferred = false;
		transferer.wake();	// finished transaction, both can return
		speaker.wake();	// wake up next speaker
		lock.release();
		return message;
	}
}


