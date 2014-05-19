package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.*;
import java.nio.ByteBuffer;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		/*int numPhysPages = Machine.processor().getNumPhysPages();
		pageTable = new TranslationEntry[numPhysPages];
		for (int i = 0; i < numPhysPages; i++)
			pageTable[i] = new TranslationEntry(i, i, true, false, false, false); */
		fileDescriptor = new OpenFile[16];
		// initialize FD 0,1 with stdin and stdout
		fileDescriptor[0] = UserKernel.console.openForReading();
		fileDescriptor[1] = UserKernel.console.openForWriting();
		pidCounterLock.acquire();
		pid = pidCounter++;
		pidCounterLock.release();
		activeProcessLock.acquire();
		++activeProcess;
		activeProcessLock.release();
		joinCondition = new Condition(joinLock);
		children = new LinkedList<UserProcess>();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		new UThread(this).setName(name).fork();
		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}
	
	public int getStatus() {
		return this.status;
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		/*Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;

		int amount = Math.min(length, memory.length - vaddr);
		System.arraycopy(memory, vaddr, data, offset, amount);

		return amount; */
		int amount = 0;
        int totalByteRead = 0;
        int pageLocation = 0;
        int lineLocation = 0;
        int physicalAddress = 0;

        Lib.assertTrue(offset >= 0 && length >= 0
        		&& offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();


        while(totalByteRead < length) {
           pageLocation = Processor.pageFromAddress(vaddr + totalByteRead);
           if (pageLocation < 0 || pageLocation > pageTable.length) 
        	   return 0;
           lineLocation = Processor.offsetFromAddress(vaddr + totalByteRead);
           if (lineLocation < 0 || lineLocation > pageSize) 
        	   return 0;
           physicalAddress = pageTable[pageLocation].ppn * pageSize + lineLocation;
           amount = Math.min(pageSize- lineLocation, length - totalByteRead);
           System.arraycopy(memory, physicalAddress, data, offset + totalByteRead, amount);
           totalByteRead += amount;
        }
        return totalByteRead;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		/*Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// for now, just assume that virtual addresses equal physical addresses
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;

		int amount = Math.min(length, memory.length - vaddr);
		System.arraycopy(data, offset, memory, vaddr, amount);

		return amount;*/
		int amount = 0;
        int totalByteWrite = 0;
        int pageLocation = 0;
        int lineLocation = 0;
        int physicalAddress = 0;

        Lib.assertTrue(offset >= 0 && length >= 0
        		&& offset + length <= data.length);
        byte[] memory = Machine.processor().getMemory();

        while (totalByteWrite < length) {
           pageLocation = Processor.pageFromAddress(vaddr + totalByteWrite);
           if (pageLocation < 0 || pageLocation > pageTable.length) 
        	   return 0;
           if (pageTable[pageLocation].readOnly == true) 
        	   return 0;
           lineLocation = Processor.offsetFromAddress(vaddr + totalByteWrite);
           if (lineLocation < 0 || lineLocation > pageSize) 
        	   return 0;
           physicalAddress = pageTable[pageLocation].ppn * pageSize + lineLocation;
           amount = Math.min(pageSize- lineLocation, length - totalByteWrite);
           System.arraycopy(data, offset + totalByteWrite, memory, physicalAddress,  amount);
           totalByteWrite += amount;
        }
        return totalByteWrite;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}
		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}
		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		/*if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;

				// for now, just assume virtual addresses=physical addresses
				section.loadPage(i, vpn);
			}
		}

		return true;*/
		boolean errorOccur = false;

        UserKernel.pagesLock.acquire();
		if (numPages > UserKernel.freePhysicalPages.size()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
		                errorOccur = true;
		}
        UserKernel.pagesLock.release();
        
        if (errorOccur == true) 
        	return false;

        UserKernel.pagesLock.acquire();
		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++)
			pageTable[i] = new TranslationEntry(i, UserKernel.freePhysicalPages.removeFirst(), true, false, false, false);
		UserKernel.pagesLock.release();
		
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
				+ " section (" + section.getLength() + " pages)");
			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				// The field TranslationEntry.readOnly should be set to true if the page is coming from a COFF section which is marked as read-only
				if (section.isReadOnly()) pageTable[vpn].readOnly = true;
				section.loadPage(i, pageTable[vpn].ppn);
			}
		}
        return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
        UserKernel.pagesLock.acquire();
        for (int i=0; i < numPages; i++) {
           UserKernel.freePhysicalPages.add(pageTable[i].ppn);
           pageTable[i] = null;
        }
        UserKernel.pagesLock.release();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}
	
	private int nextAvailFD() {
		for(int i = 0; i < fileDescriptor.length; i++) {
			if(fileDescriptor[i] == null)
				return i;
		}
		return -1;
	}
	
	private boolean checkFDIndex(int fdIndex) {
		if(fdIndex < 0 || fdIndex > 15)
			return false;
		return true;
	}
	
	private boolean checkThreeArgs(int fdIndex, int buffer, int count) {
		if(!checkFDIndex(fdIndex) || count < 0)
			return false;
		return true;
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		if(pid == 0)
			Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}
	
	/**
	 * Attempt to open the named file and return a file descriptor.
	 *
	 * Note that open() can only be used to open files on disk; open() will never
	 * return a file descriptor referring to a stream.
	 *
	 * Returns the new file descriptor, or -1 if an error occurred.
	 */
	private int handleOpen(int fileNameAddr) {
		OpenFile open;
		open = UserKernel.fileSystem.open(readVirtualMemoryString(fileNameAddr, 256), false);
		if(open == null) // file does not exist
			return -1;
		else {
			// get the next available FD index
			int availableFD = nextAvailFD();
			// check to see if the # of currently opened files > 16
			if(availableFD == -1) {
				return -1;
			}
			// make FD point to the newly opened file
			fileDescriptor[availableFD] = open;
			return availableFD;
		}
	}
	
	private int handleClose(int fdIndex) {
		if(!checkFDIndex(fdIndex))
			return -1;
		// close this file and release any associated system resources
		fileDescriptor[fdIndex].close();
		fileDescriptor[fdIndex] = null;
		return 0;
	}
	
	private int handleCreate(int fileNameAddr) {
		OpenFile create;
		create = UserKernel.fileSystem.open(readVirtualMemoryString(fileNameAddr, 256), true);
		if(create == null)	
			return -1;
		// get the next available FD index
		int availableFD = nextAvailFD();
		// check to see if the # of currently opened files > 16
		if(availableFD == -1)	
			return -1;
		// make FD point to the newly created file
		fileDescriptor[availableFD] = create;
		return availableFD;
	}
	
	private int handleRead(int fdIndex, int buffer, int count) {
		if(!checkThreeArgs(fdIndex, buffer, count)) {
			return -1;
		}
		int bufsize = 1024;
		int bytesWritten = 0;
		int bytesLeft = count;
		int successfulWrite = 0;
		OpenFile fileToBeRead = fileDescriptor[fdIndex];
		byte[] buf = new byte[bufsize];
		while(bytesLeft > 0) {
			if(bytesLeft >= bufsize) {
				if(fileToBeRead.read(buf, 0, bufsize) == -1)
					return -1;
				successfulWrite += writeVirtualMemory(buffer+bytesWritten, buf, 0, bufsize);
				bytesLeft -= bufsize;
				bytesWritten += bufsize;
			}
			else {
				if(fileToBeRead.read(buf, 0, bytesLeft) == -1) 
					return -1;
				successfulWrite = writeVirtualMemory(buffer+bytesWritten, buf, 0, bytesLeft);
				bytesWritten += bytesLeft;
				bytesLeft = 0;
			}	
		}
		return successfulWrite;
	}
	
	private int handleWrite(int fdIndex, int buffer, int count) {
		if(!checkThreeArgs(fdIndex, buffer, count)) {
			return -1;
		}
		int bufsize = 1024;
		int bytesWritten = 0;
		int bytesLeft = count;
		int successfulWrite = 0;
		
		int transferred, written;
		
		OpenFile fileToBeWritten = fileDescriptor[fdIndex];
		byte[] buf = new byte[bufsize];
		while(bytesLeft > 0) {
			if (bytesLeft >= bufsize) {
				transferred = readVirtualMemory(buffer+bytesWritten, buf, 0, bufsize);
				written = fileToBeWritten.write(buf, 0, bufsize);
				if (transferred != written)
		        	 return -1;
				else {
					bytesLeft -= bufsize;
					bytesWritten += bufsize; 
					successfulWrite += written;
				}
		   }
		   else {
			   transferred = readVirtualMemory(buffer+bytesWritten, buf, 0, bytesLeft);
			   written = fileToBeWritten.write(buf, 0, bytesLeft);
			   if (transferred != written)
				   return -1; 
			   else {
		          bytesWritten += bytesLeft;
		          successfulWrite += written;
		          bytesLeft = 0;
			   }
		   }
		}
		return successfulWrite;
	}
	
	private int handleUnlink(int fileNameAddr) {
		String file = readVirtualMemoryString(fileNameAddr, 256);
		boolean success;
		success = UserKernel.fileSystem.remove(file);
		if(success)
			return 0;
		return -1;
	}
	
	private int handleExec(int fileNamePtr, int argc, int argvPtr) {
		if(argc < 0)
			return -1;
		String fileName = readVirtualMemoryString(fileNamePtr, 256);
		String [] argv = new String[argc];
		for(int i = 0; i < argc; i++) {
			byte [] byteAddr = new byte[4];
			readVirtualMemory(argvPtr + (i * 4), byteAddr);
			int argvAddr = Lib.bytesToInt(byteAddr, 0);
			argv[i] = readVirtualMemoryString(argvAddr, 256);
		}
		UserProcess newChild = UserProcess.newUserProcess();
		children.add(newChild);
		newChild.parent = this;
		if(newChild.execute(fileName, argv)) {
			return newChild.pid;
		}
		return -1;
	}
	
	private int handleJoin(int processID, int statusPtr) {
		UserProcess childProcess = null;
		for(UserProcess child : children) {
			if(child.pid == processID) {
				childProcess = child;
			}
		}
		if(childProcess == null)
			return -1;
		if(!childProcess.hasExit) {
			joinLock.acquire();
			this.joiningChild = childProcess;
			joinCondition.sleep();
			joinLock.release();
			// disown child process
			children.remove(childProcess);
			// write child process's exit status into memory
			ByteBuffer b = ByteBuffer.allocate(4);
			byte[] status = b.putInt(childProcess.status).array();
			writeVirtualMemory(statusPtr, status);
		}
		if(childProcess.normalExit)
			return 1;
		return 0;
	}
	
	private void handleExit(int status) {
		// close fileDescriptors
		for(OpenFile fd : fileDescriptor) {
			if(fd != null) {
				fd.close();
				fd = null;
			}
		}
		// close coff section
		coff.close();
		this.status = status;
		unloadSections();
		hasExit = true;
		activeProcessLock.acquire();
		activeProcess--;
		if(parent != null) {
			if(this == parent.joiningChild) {
				parent.joinLock.acquire();
				parent.joinCondition.wake();
				parent.joinLock.release();
			}
		}
		//parent.children.remove(this);
		if(activeProcess > 1) {
			activeProcessLock.release();
			KThread.finish();
		}
		else {
			activeProcessLock.release();
			UserKernel.kernel.terminate();
		}
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallExit:
			handleExit(a0);
			break;
		default:
			normalExit = false;
			handleExit(-1);
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;
	
	protected OpenFile[] fileDescriptor;

	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';
	
	public static Lock pidCounterLock = new Lock();
	
	public static Lock activeProcessLock = new Lock();
	
	private Lock joinLock = new Lock();
	
	private Condition joinCondition;
	
	public static int pidCounter;
	
	public static int activeProcess;
	
	private LinkedList<UserProcess> children;
	
	private int pid;
	
	private int status;
	
	private boolean normalExit = true;
	
	//private KThread processThread;
	
	private UserProcess parent;
	
	private UserProcess joiningChild;
	
	private boolean hasExit;
}
