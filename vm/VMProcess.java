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
				int correctVpn = VMKernel.ipt[entry.ppn].getEntry().vpn;
				pageTable[correctVpn] = new TranslationEntry(pageTable[correctVpn].vpn,
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

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
		// check if entry is dirty
		// free up the swap space upon swapping the page into memory
                VMKernel.freeSwapPagesLockAcquire();
                for( int index =0 ;index < VMKernel.freeSwapPages.size(); index++)
                {
                   if (	VMKernel.freeSwapPages.get(index).getProcessID()== processID())
                      VMKernel.freeSwapPages.get(index).setOccupied(false);
                }
                VMKernel.freeSwapPagesLockRelease();
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
		int ppn = allocatePhysicalPage(entry);
		entry.ppn = ppn;
		VMKernel.pinPage(ppn);
		if (entry.dirty) { // swap in
			int index = entry.vpn;	// index at the freeSwapSpace LinkedList
			VMKernel.swapFile.read(index * pageSize, Machine.processor()
					.getMemory(), entry.ppn * pageSize, pageSize);

		} else {
                        if (entry.vpn < coff.getNumSections())
			{ // entry.vpn is section number
			  CoffSection section = coff.getSection(entry.vpn);
			  section.loadPage((vpn - section.getFirstVPN()), ppn);
                        }
                        else
                        {
		          //initialize stack frame in the first time
                           byte[] nullPage = new byte[pageSize];
                           for (int i =0 ; i< pageSize; i++)
                           {
                              nullPage[i]=0;
                           }
                           System.arraycopy(nullPage, 0,  Machine.processor()
					.getMemory(), entry.ppn*pageSize, pageSize);
                           
                        }
		}			
		VMKernel.unpinPage(ppn);
		entry.valid = true;
		// sync page table and inverted page table
		pageTable[vpn] = entry;
		UserKernel.memoryLockAcquire();
		VMKernel.iptLockAcquire();
		VMKernel.ipt[entry.ppn].setProcessID(processID());
		TranslationEntry entryForIpt = new TranslationEntry(vpn, entry.ppn, 
				entry.valid, entry.readOnly, entry.used, entry.dirty);
		// same reason as above
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
		// try to find an invalid TLB entry to evict
		VMKernel.tlbLockAcquire();
		for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
			if (!Machine.processor().readTLBEntry(i).valid) {
				VMKernel.tlbLockRelease();
				return i;
			}
		}
		VMKernel.tlbLockRelease();
		// all entries are valid, randomly evict a victim
		int victimIndex = Lib.random(Machine.processor().getTLBSize());
		TranslationEntry victim = Machine.processor().readTLBEntry(victimIndex);
		victim.valid = false;
		// sync invalidated victim entry back to TLB
		VMKernel.tlbLockAcquire();
		Machine.processor().writeTLBEntry(victimIndex, victim);
		VMKernel.tlbLockRelease();
		int correctVpn = VMKernel.ipt[victim.ppn].getEntry().vpn;
		// sync entry with page table
		pageTable[correctVpn] = new TranslationEntry(pageTable[victim.vpn].vpn,
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
		TranslationEntry newEntry = new TranslationEntry(vpn, entry.ppn, 
				entry.valid, entry.readOnly, entry.used, entry.dirty);
		VMKernel.tlbLockAcquire();
		Machine.processor().writeTLBEntry(tlbIndex, newEntry);
		VMKernel.tlbLockRelease();
	}

	private int allocatePhysicalPage(TranslationEntry entry) {
		if (UserKernel.freePages.isEmpty() )
                   clockAlgorithm();

                UserKernel.memoryLockAcquire();
                int tempPage = ((Integer) UserKernel.freePages.removeFirst()).intValue();
                UserKernel.memoryLockRelease();

                return tempPage;
	}

	private void clockAlgorithm() {
		int clockHand = 0;
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
					
					// sync pageTable entry
					pageTable[victim.vpn].valid = false;
					
					// sync ipt entry, keeping vpn as the vpn that causes the page fault
					UserKernel.memoryLockAcquire();
					VMKernel.iptLockAcquire();
					VMKernel.ipt[victim.ppn].setEntry(victim);
					VMKernel.iptLockRelease();
					UserKernel.memoryLockRelease();
					
				}
			}
			clockHand = (clockHand + 1) % Machine.processor().getNumPhysPages();
		}
		return;
	}

	private int assignSwapSpace() {
                int swapIndex = 0;
		VMKernel.freeSwapPagesLockAcquire();
		for (int i = 0; i < VMKernel.freeSwapPages.size(); i++) {
			// false means not in use
			if (VMKernel.freeSwapPages.get(i).getOccupied() == false) {
				VMKernel.freeSwapPages.get(i).setOccupied(true);
                                VMKernel.freeSwapPages.get(i).setProcessID(processID);
                                swapIndex = i;
				return swapIndex;
			}
		}
                
		VMKernel.freeSwapPages.add(new VMKernel.indexAtFreeSwapPages(processID, true));
                int SwapIndex = VMKernel.freeSwapPages.size() - 1;
		VMKernel.freeSwapPagesLockRelease();
		return swapIndex;
	}

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';


}
