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
		freeSwapPages = new LinkedList<Boolean>();
		iptLock = new Lock();
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
	
	public static LinkedList<Boolean> freeSwapPages;
	
	public static PageTableEntryInfo[] ipt;
	
	public static Lock iptLock;
	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';

	public static void pinPage(int ppn) {
		iptLock.acquire();
		ipt[ppn].setPinCount(++ipt[ppn].pinCount);
		iptLock.release();
	}

	public static void unpinPage(int ppn) {
		iptLock.acquire();
		ipt[ppn].setPinCount(--ipt[ppn].pinCount);
		iptLock.release();
	}
}
