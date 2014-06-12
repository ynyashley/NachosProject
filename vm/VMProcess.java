package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import nachos.vm.VMKernel.PageTableEntryInfo;
import java.util.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		swapPageIndices = new LinkedList<Integer>();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		/*
		 * Flush TLB on context switch
		 */
		System.err.println("===============saveState()===============");
		for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
			TranslationEntry entry = Machine.processor().readTLBEntry(i);
			// sync entry with page table if entry is valid
			if (entry.valid) {
				System.out.println("-------syncing with pageTable--------");
				int correctVpn = VMKernel.ipt[entry.ppn].getEntry().vpn;
				pageTable[correctVpn] = new TranslationEntry(entry.vpn,
						entry.ppn, entry.valid, entry.readOnly, entry.used,
						entry.dirty);
				UserKernel.memoryLockAcquire();
				VMKernel.iptLockAcquire();
				VMKernel.ipt[entry.ppn].setEntry(new TranslationEntry(correctVpn,
						entry.ppn, entry.valid, entry.readOnly, entry.used,
						entry.dirty));
				VMKernel.iptLockRelease();
				UserKernel.memoryLockRelease();
			}
			// invalidate entry
			//entry.valid = false;
			//Machine.processor().writeTLBEntry(i, entry);
			Machine.processor().writeTLBEntry(i, new TranslationEntry());
		}
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		//super.restoreState();
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		System.err.println("===========loadSections()==============");
		pageTable = new TranslationEntry[numPages];

		for (int vpn = 0; vpn < numPages; vpn++) {
			pageTable[vpn] = new TranslationEntry(vpn, -1, false, false, false,
					false);
		}

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;

				pageTable[vpn].readOnly = section.isReadOnly();
				pageTable[vpn].vpn = s;
			}
		}
		
		for(int i = 0; i < swapPageIndices.size(); i++) {
			 swapPageIndices.set(i, -1);
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
		// check if entry is dirty
		// free up the swap space upon swapping the page into memory
		for(TranslationEntry entry : pageTable) {
			int index = swapPageIndices.get(entry.vpn);
			if(entry.dirty && index != -1) {
			    VMKernel.freeSwapPagesLockAcquire();
				VMKernel.freeSwapPages.set(index, false);
				VMKernel.freeSwapPagesLockRelease();
			}
		}
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause
	 *            the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();
		int virtualAddress = processor.readRegister(Processor.regBadVAddr);
		switch (cause) {
		case Processor.exceptionTLBMiss:
			handleTLBMiss(virtualAddress);
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	private void handleTLBMiss(int virtualAddress) {
		int vpn = Processor.pageFromAddress(virtualAddress);
		TranslationEntry entry = pageTable[vpn];

		/*
		 * If we found that the entry recorded on the pageTable
		 * is not valid, it is a page fault 
		 * (vpn->ppn mapping recorded on pageTable is unusable).
		 * Now we have to load the entry into memory by first
		 * looking for it in swap space or CoffSection.
		 */
		 
		if (!entry.valid) {
			entry = handlePageFault(entry, vpn);
		}
		int tlbIndex = allocateTLBEntry();
		updateTLBEntry(tlbIndex, entry, vpn);
	}
	
	private TranslationEntry handlePageFault(TranslationEntry entry, int vpn) {
		System.err.println("===========handlePageFault()==============");
		System.out.println("TLB entry.valid at index 0: " + Machine.processor().readTLBEntry(0).valid);
		int ppn = allocatePhysicalPage(entry);
		entry.ppn = ppn;
		VMKernel.pinPage(ppn);
		if (entry.dirty) { // swap in
			int index = entry.vpn;	// index at the freeSwapSpace LinkedList
			VMKernel.swapFile.read(index * pageSize, Machine.processor()
					.getMemory(), entry.ppn * pageSize, pageSize);

		} else {
			// entry.vpn is section number
			System.err.println("entry.vpn: " + entry.vpn);
			System.err.println("vpn: " + vpn);
			CoffSection section = coff.getSection(entry.vpn);
			section.loadPage((vpn - section.getFirstVPN()), ppn);
		}			
		VMKernel.unpinPage(ppn);
		entry.valid = true;
		// sync page table and inverted page table
		pageTable[vpn] = entry;
		UserKernel.memoryLockAcquire();
		VMKernel.iptLockAcquire();
		VMKernel.ipt[entry.ppn].setProcessID(processID());
		VMKernel.iptLockRelease();
		TranslationEntry entryForIpt = new TranslationEntry(vpn, entry.ppn, 
				entry.valid, entry.readOnly, entry.used, entry.dirty);
		// same reason as above
		VMKernel.iptLockAcquire();
		VMKernel.ipt[entry.ppn].setEntry(entryForIpt);
		VMKernel.iptLockRelease();
		UserKernel.memoryLockRelease();
		return entry;
	}

	/** 
	 * Allocate a page in the TLB to make room for the page
	 * that is ready to be loaded from swap space or CoffSection.
	 * 
	 * To do so, we first try to find an invalid TLB entry to evict,
	 * but if all entries are valid, we randomly pick a victim
	 * for eviction.
	 *
	 * @return the index of the newly allocated space on the TLB array.
	 **/
	private int allocateTLBEntry() {
		System.err.println("===========allocateTLBEntry()==============");
		// try to find an invalid TLB entry to evict
		VMKernel.tlbLockAcquire();
		for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
			if (!Machine.processor().readTLBEntry(i).valid) {
				System.err.println("incorrect entry at: " + i);
				VMKernel.tlbLockRelease();
				return i;
			}
		}
		VMKernel.tlbLockRelease();
		// all entries are valid, randomly evict a victim
		int victimIndex = Lib.random(Machine.processor().getTLBSize());
		TranslationEntry victim = Machine.processor().readTLBEntry(victimIndex);
		System.err.println("evicted TLB entry.vpn: " + victim.vpn);
		victim.valid = false;
		// sync invalidated victim entry back to TLB
		VMKernel.tlbLockAcquire();
		Machine.processor().writeTLBEntry(victimIndex, victim);
		VMKernel.tlbLockRelease();
		int correctVpn = VMKernel.ipt[victim.ppn].getEntry().vpn;
		System.err.println("correctVpn: " + correctVpn);
		// sync entry with page table
		pageTable[correctVpn] = new TranslationEntry(victim.vpn,
				victim.ppn, victim.valid, victim.readOnly, victim.used,
				victim.dirty);
		// sync victim entry with ipt, keeping vpn the one that causes the page fault
		UserKernel.memoryLockAcquire();
		victim.vpn = correctVpn;
		VMKernel.iptLockAcquire();
		VMKernel.ipt[victim.ppn].setEntry(victim);
		VMKernel.iptLockRelease();
		UserKernel.memoryLockRelease();
		return victimIndex;
	}
	
	private void updateTLBEntry(int tlbIndex, TranslationEntry entry, int vpn) {
		System.err.println("==============updateTLBEntry()==============");
		TranslationEntry newEntry = new TranslationEntry(vpn, entry.ppn, 
				entry.valid, entry.readOnly, entry.used, entry.dirty);
		System.out.println("tlbIndex: " + tlbIndex);
		System.out.println("entry.valid: " + entry.valid);
		VMKernel.tlbLockAcquire();
		Machine.processor().writeTLBEntry(tlbIndex, newEntry);
		VMKernel.tlbLockRelease();
	}

	private int allocatePhysicalPage(TranslationEntry entry) {
		System.err.println("==============allocatePhysicalPage=================");
		System.out.println("TLB entry.valid at index 0: " + Machine.processor().readTLBEntry(0).valid);
		return UserKernel.freePages.isEmpty() ? clockAlgorithm() : 
				((Integer) UserKernel.freePages.removeFirst()).intValue();
	}

	private int clockAlgorithm() {
		System.err.println("===========clockAlgorithm()==============");
		System.out.println("TLB entry.valid at index 0: " + Machine.processor().readTLBEntry(0).valid);
		int clockHand = 0;
		boolean swappedOut = false;
		TranslationEntry victim = null;
		while (UserKernel.freePages.isEmpty()) {
			PageTableEntryInfo frame = VMKernel.ipt[clockHand];
			if (frame.getPinCount() < 1) { // swap
				if (frame.getEntry().used) {
					frame.getEntry().used = false;
				} else {
					int index = 0;
					victim = frame.getEntry();
					/* write the page out to swap file if entry is dirty,
					 * simply evict otherwise. 
					 */
					if (victim.dirty) { 
						System.err.println("-----------swapped out------------");
						swappedOut = true;
						index = assignSwapSpace();
						// write to swap file
						VMKernel.freeSwapPagesLockAcquire();
						VMKernel.swapFile.write(index * pageSize, 
								Machine.processor().getMemory(),
								victim.ppn * pageSize, pageSize);
						VMKernel.freeSwapPagesLockRelease();
					}
					// begin eviction
					UserKernel.memoryLockAcquire();
					UserKernel.freePages.add(victim.ppn);
					UserKernel.memoryLockRelease();
					victim.valid = false; // invalidate PTE
					System.err.println("victim.vpn: " + victim.vpn);
					
					// sync pageTable entry
					pageTable[victim.vpn].valid = false;
					
					// sync ipt entry, keeping vpn as the vpn that causes the page fault
					UserKernel.memoryLockAcquire();
					VMKernel.iptLockAcquire();
					VMKernel.ipt[victim.ppn].setEntry(victim);
					VMKernel.iptLockRelease();
					UserKernel.memoryLockRelease();
					
					/* reuse victim's TranslationEntry vpn field to store 
					 * the index of which its page in swap file can be found 
					 */
					if(swappedOut) {
						System.err.println("----------here, not good------------");
						swapPageIndices.set(victim.vpn, index);
						victim.vpn = index;
					}
				}
			}
			clockHand = (clockHand + 1) % Machine.processor().getNumPhysPages();
		}
		return victim.ppn;
	}

	private int assignSwapSpace() {
		System.err.println("===========assignSwapSpace()==============");
		VMKernel.freeSwapPagesLockAcquire();
		for (int i = 0; i < VMKernel.freeSwapPages.size(); i++) {
			// false means not in use
			if (VMKernel.freeSwapPages.get(i) == false) {
				VMKernel.freeSwapPages.set(i, true);
				return i;
			}
		}
		VMKernel.freeSwapPages.add(new Boolean(true));
		VMKernel.freeSwapPagesLockRelease();
		return (VMKernel.freeSwapPages.size() - 1);
	}
	
	private LinkedList<Integer> swapPageIndices;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
