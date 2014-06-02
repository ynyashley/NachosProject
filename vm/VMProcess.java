package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import nachos.vm.VMKernel.PageTableEntryInfo;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		pageTableLock = new Lock();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		/*
		 * Flush TLB on context switch
		 */
		for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
			TranslationEntry entry = Machine.processor().readTLBEntry(i);
			// sync entry with page table if entry is valid
			if (entry.valid) {
				pageTableLock.acquire();
				pageTable[entry.vpn] = new TranslationEntry(entry.vpn,
						entry.ppn, entry.valid, entry.readOnly, entry.used,
						entry.dirty);
				pageTableLock.release();
				VMKernel.iptLock.acquire();
				VMKernel.ipt[entry.ppn].setEntry(entry);
				VMKernel.iptLock.release();
			}
			// invalidate entry
			entry.valid = false;
			Machine.processor().writeTLBEntry(i, entry);
		}
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		super.restoreState();
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		return super.loadSections();
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
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
		// Get VPN
		int vpn = Processor.pageFromAddress(virtualAddress);
		TranslationEntry entry = pageTable[vpn];

		if (entry.valid) {
			boolean invalidEntryFound = false;
			// try to find an invalid TLB entry to evict
			for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
				if (!Machine.processor().readTLBEntry(i).valid) {
					invalidEntryFound = true;
					// do replacement
					Machine.processor().writeTLBEntry(i, entry);
					break;
				}
			}
			if (!invalidEntryFound) {
				// randomly evict a victim
				int victimIndex = Lib.random(Machine.processor().getTLBSize());
				TranslationEntry victim = Machine.processor().readTLBEntry(
						victimIndex);
				// sync entry with page table
				pageTableLock.acquire();
				pageTable[victim.vpn] = new TranslationEntry(victim.vpn,
						victim.ppn, victim.valid, victim.readOnly, victim.used,
						victim.dirty);
				pageTableLock.release();
				VMKernel.iptLock.acquire();
				VMKernel.ipt[victim.ppn].setEntry(victim);
				VMKernel.iptLock.release();
				// update
				Machine.processor().writeTLBEntry(victimIndex, entry);
			}
		} else { // page fault
			handlePageFault(entry, vpn);
		}
	}

	private void handlePageFault(TranslationEntry entry, int vpn) {
		int ppn = allocatePhysicalPage(entry);
		if (entry.dirty) { // swap in
			int index = entry.ppn;
			entry.ppn = ppn;
			// TODO: may not pinPage
			VMKernel.pinPage(ppn);
			entry.valid = true;
			VMKernel.swapFile.read(index * pageSize, Machine.processor()
					.getMemory(), entry.ppn * pageSize, pageSize);
			VMKernel.freeSwapPages.set(index, false);
			VMKernel.unpinPage(ppn);
		} else {
			entry.ppn = ppn;
			VMKernel.pinPage(ppn);
			entry.valid = true;
			CoffSection section = coff.getSection(entry.vpn);
			section.loadPage((vpn - section.getFirstVPN()), ppn);
			VMKernel.unpinPage(ppn);
		}
		// sync page table and inverted page table
		pageTable[entry.vpn] = entry;
		VMKernel.ipt[ppn].setProcessID(processID());
		VMKernel.ipt[ppn].setEntry(entry);
	}

	private int allocatePhysicalPage(TranslationEntry entry) {
		int ppn = 0;
		if (UserKernel.freePages.isEmpty()) { // no free memory, need to evict a page
			ppn = clockAlgorithm(); // select a victim for replacement

		} else { // allocate a free page from list
			ppn = ((Integer) UserKernel.freePages.removeFirst()).intValue();
			// TODO: just keep the old values of the other bits?
			pageTable[entry.vpn] = new TranslationEntry(entry.vpn, ppn,
					entry.valid, entry.readOnly, entry.used, entry.dirty);
		}
		return ppn;
	}

	private int clockAlgorithm() {
		int clockHand = 0;
		TranslationEntry victim = null;
		while (UserKernel.freePages.isEmpty()) {
			PageTableEntryInfo frame = VMKernel.ipt[clockHand];
			if (frame.getPinCount() < 1) { // swap
				if (frame.getEntry().used) {
					frame.getEntry().used = false;
				} else {
					victim = frame.getEntry();
					if (victim.dirty) { // swap the page out
						int index = assignSwapSpace();
						// byte [] memory = new byte[pageSize];
						// TODO: not sure about victim.vpn*pageSize
						// readVirtualMemory(victim.vpn*pageSize, memory);
						VMKernel.swapFile.write(index * pageSize, Machine
								.processor().getMemory(),
								victim.ppn * pageSize, pageSize);
						victim.ppn = index;
					}
					UserKernel.freePages.add(victim.ppn);
					victim.valid = false; // invalidate PTE
					pageTable[victim.vpn].valid = false;
					// Invalidate TLB entry if it is there
					for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
						int vpn = Machine.processor().readTLBEntry(i).vpn;
						if (victim.vpn == vpn) {
							Machine.processor().writeTLBEntry(i, victim);
							break;
						}
					}
				}
			}
			clockHand = (clockHand + 1) % Machine.processor().getNumPhysPages();
		}
		return victim.ppn;
	}

	private int assignSwapSpace() {
		for (int i = 0; i < VMKernel.freeSwapPages.size(); i++) {
			if (VMKernel.freeSwapPages.get(i) == false) {
				VMKernel.freeSwapPages.set(i, true);
				return i;
			}
		}
		VMKernel.freeSwapPages.add(new Boolean(true));
		return (VMKernel.freeSwapPages.size() - 1);
	}
	
	private static Lock pageTableLock;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
