package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.util.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		ipt = new PageTableEntryInfo[Machine.processor().getNumPhysPages()];
		for(int i = 0; i < ipt.length; i++) {
			ipt[i] = new PageTableEntryInfo();
		}
		freeSwapPages = new LinkedList<indexAtFreeSwapPages>();
		iptLock = new Lock();
		tlbLock = new Lock();
		freeSwapPagesLock = new Lock();
		swapFile = fileSystem.open("swapFile", true);
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}
	
	public class PageTableEntryInfo {
		private int processID;
		private TranslationEntry entry;
		private int pinCount;
		
		public PageTableEntryInfo() {
		}
		
		public int getProcessID() {
			return processID;
		}
		public void setProcessID(int processID) {
			this.processID = processID;
		}
		public TranslationEntry getEntry() {
			return entry;
		}
		public void setEntry(TranslationEntry entry) {
			this.entry = entry;
		}
		public int getPinCount() {
			return pinCount;
		}
		public void setPinCount(int pinCount) {
			this.pinCount = pinCount;
		}
	}
	
       public static OpenFile swapFile;
	
       static class indexAtFreeSwapPages {
 	   private int processID;
           private boolean occupied;
		
	   public indexAtFreeSwapPages() {
	   }
	
           public indexAtFreeSwapPages(int processID, boolean occupied) {
              this.processID = processID;
              this.occupied = occupied;
	   }
	
	   public int getProcessID() {
	      return processID;
	   }
	
           public void setProcessID(int processID) {
	      this.processID = processID;
	   }
	
           public boolean getOccupied() {
	      return occupied;
	   }
	
           public void setOccupied(boolean occupied) {
	     this.occupied = occupied;
	   }
	}

        public static LinkedList<indexAtFreeSwapPages> freeSwapPages;
	
	public static PageTableEntryInfo[] ipt;
	
	public static Lock iptLock;
	
	public static Lock tlbLock;
	
	public static Lock freeSwapPagesLock;
	
	private static boolean iptLockAcquired;
	
	private static boolean tlbLockAcquired;
	
	private static boolean freeSwapPagesLockAcquired;
	
	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';

	public static void pinPage(int ppn) {
		iptLockAcquire();
		ipt[ppn].setPinCount(++ipt[ppn].pinCount);
		iptLockRelease();
	}

	public static void unpinPage(int ppn) {
		iptLockAcquire();
		ipt[ppn].setPinCount(--ipt[ppn].pinCount);
		iptLockRelease();
	}
	
	public static void iptLockAcquire() {
		if(!VMKernel.iptLock.isHeldByCurrentThread() && !iptLockAcquired) {
			VMKernel.iptLock.acquire();
			iptLockAcquired = true;
		}
	}
	
	public static void iptLockRelease() {
		if(!VMKernel.iptLock.isHeldByCurrentThread() && iptLockAcquired) {
			VMKernel.iptLock.release();
			iptLockAcquired = false;
		}
	}
	
	public static void tlbLockAcquire() {
	//	System.err.println("tlbLockAcquire: " + VMKernel.tlbLock.isHeldByCurrentThread());
		if(!VMKernel.tlbLock.isHeldByCurrentThread() && !tlbLockAcquired) {
			VMKernel.tlbLock.acquire();
			tlbLockAcquired = true;
		}
	}
	
	public static void tlbLockRelease() {
		if(!VMKernel.tlbLock.isHeldByCurrentThread() && tlbLockAcquired) {
			VMKernel.tlbLock.release();
			tlbLockAcquired = false;
		}
	}
	
	public static void freeSwapPagesLockAcquire() {
		if(!VMKernel.freeSwapPagesLock.isHeldByCurrentThread() && !freeSwapPagesLockAcquired) {
			VMKernel.freeSwapPagesLock.acquire();
			freeSwapPagesLockAcquired = true;
		}
	}
	
	public static void freeSwapPagesLockRelease() {
		if(!VMKernel.freeSwapPagesLock.isHeldByCurrentThread() && freeSwapPagesLockAcquired) {
			VMKernel.freeSwapPagesLock.release();
			freeSwapPagesLockAcquired = false;
		}
	}
}
