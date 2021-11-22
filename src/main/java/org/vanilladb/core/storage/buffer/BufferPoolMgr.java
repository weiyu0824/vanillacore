/*******************************************************************************
 * Copyright 2016, 2017 vanilladb.org contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.vanilladb.core.storage.buffer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.vanilladb.core.server.VanillaDb;
import org.vanilladb.core.storage.file.BlockId;
import org.vanilladb.core.storage.file.FileMgr;
import org.vanilladb.core.util.TransactionProfiler;

/**
 * Manages the pinning and unpinning of buffers to blocks.
 */
class BufferPoolMgr {
	static {
		System.out.println("won't swap Index buffer----------------------------------------------");
	}
	
	private Buffer[] bufferPool;
	private Map<BlockId, Buffer> blockMap;
	private volatile int lastReplacedBuff;
	private AtomicInteger numAvailable;
	
	private AtomicInteger totalCount;
	private AtomicInteger missCount;

	// Optimization: Lock striping
	private Object[] anchors = new Object[1009];

	/**
	 * Creates a buffer manager having the specified number of buffer slots.
	 * This constructor depends on both the {@link FileMgr} and
	 * {@link org.vanilladb.core.storage.log.LogMgr LogMgr} objects that it gets
	 * from the class {@link VanillaDb}. Those objects are created during system
	 * initialization. Thus this constructor cannot be called until
	 * {@link VanillaDb#initFileAndLogMgr(String)} or is called first.
	 * 
	 * @param numBuffs
	 *            the number of buffer slots to allocate. Must be at least 2.
	 */
	BufferPoolMgr(int numBuffs) {
		bufferPool = new Buffer[numBuffs];
		blockMap = new ConcurrentHashMap<BlockId, Buffer>(numBuffs);
		numAvailable = new AtomicInteger(numBuffs);
		totalCount = new AtomicInteger();
		missCount = new AtomicInteger();
		lastReplacedBuff = 0;
		for (int i = 0; i < numBuffs; i++)
			bufferPool[i] = new Buffer();

		for (int i = 0; i < anchors.length; ++i) {
			anchors[i] = new Object();
		}
	}

	// Optimization: Lock striping
	private Object prepareAnchor(Object o) {
		int code = o.hashCode() % anchors.length;
		if (code < 0)
			code += anchors.length;
		return anchors[code];
	}

	/**
	 * Flushes all dirty buffers.
	 */
	void flushAll() {
		TransactionProfiler profiler = TransactionProfiler.getLocalProfiler();
		for (Buffer buff : bufferPool) {
			// OPTIMIZATION: Get the external lock of those non-index buffers,
			// because they might be swapped
			if (buff.block() == null || !buff.block().fileName().contains(".idx")) {
				try {
					profiler.startComponentProfiler("Buffer External Lock");
					buff.getExternalLock().lock();
					profiler.stopComponentProfiler("Buffer External Lock");
					buff.flush();
				} finally {
					buff.getExternalLock().unlock();
				}
			} else {
				// directly flush idx buffer, because we won't swap it
				buff.flush();
			}
		}
	}

	/**
	 * Pins a buffer to the specified block. If there is already a buffer
	 * assigned to that block then that buffer is used; otherwise, an unpinned
	 * buffer from the pool is chosen. Returns a null value if there are no
	 * available buffers.
	 * 
	 * @param blk
	 *            a block ID
	 * @return the pinned buffer
	 */
	Buffer pin(BlockId blk) {
		TransactionProfiler profiler = TransactionProfiler.getLocalProfiler();
		profiler.startComponentProfiler("Prepare Anchor in BufferPoolMgr Pin");
		// Only the txs acquiring the same block will be blocked
		synchronized (prepareAnchor(blk)) {
			profiler.stopComponentProfiler("Prepare Anchor in BufferPoolMgr Pin");
			// Find existing buffer
			Buffer buff = findExistingBuffer(blk);

			totalCount.incrementAndGet();
			// If there is no such buffer
			if (buff == null) {
				
				missCount.incrementAndGet();
				// Choose Unpinned Buffer
				int lastReplacedBuff = this.lastReplacedBuff;
				int currBlk = (lastReplacedBuff + 1) % bufferPool.length;
				// Note: this check will fail if there is only one buffer
				while (currBlk != lastReplacedBuff) {
					buff = bufferPool[currBlk];
					
					// OPTIMIZATION: Swap non-index buffers
					if (buff.block() == null || !buff.block().fileName().contains(".idx")) {
						// Get the lock of buffer if it is free
						profiler.startComponentProfiler("Buffer External Lock");
						if (buff.getExternalLock().tryLock()) {
							profiler.stopComponentProfiler("Buffer External Lock");
							try {
								// Check if there is no one use it
								if (!buff.isPinned() && !buff.checkRecentlyPinnedAndReset()) {
									this.lastReplacedBuff = currBlk;
									
									// Swap
									BlockId oldBlk = buff.block();
									if (oldBlk != null)
										blockMap.remove(oldBlk);
									buff.assignToBlock(blk);
									blockMap.put(blk, buff);
									if (!buff.isPinned())
										numAvailable.decrementAndGet();
									
									// Pin this buffer
									buff.pin();
									return buff;
								}
							} finally {
								// Release the lock of buffer
								buff.getExternalLock().unlock();
							}
						}
					}
					currBlk = (currBlk + 1) % bufferPool.length;
				}
				return null;
				
			// If it exists
			} else {
				// OPTIMIZATION: Early return index buffers.
				// We don't have to pin the index buffers more than one time
				// because we won't unpin those index buffers.
				if (buff.block().fileName().contains(".idx")) {
					return buff;
				}
				// Get the lock of buffer
				profiler.startComponentProfiler("Buffer External Lock");
				buff.getExternalLock().lock();
				profiler.stopComponentProfiler("Buffer External Lock");
				
				try {
					// Check its block id before pinning since it might be swapped
					if (buff.block().equals(blk)) {
						if (!buff.isPinned())
							numAvailable.decrementAndGet();
						buff.pin();
						return buff;
					}
					return pin(blk);
					
				} finally {
					// Release the lock of buffer
					buff.getExternalLock().unlock();
				}
			}
		}
	}

	/**
	 * Allocates a new block in the specified file, and pins a buffer to it.
	 * Returns null (without allocating the block) if there are no available
	 * buffers.
	 * 
	 * @param fileName
	 *            the name of the file
	 * @param fmtr
	 *            a pageformatter object, used to format the new block
	 * @return the pinned buffer
	 */
	Buffer pinNew(String fileName, PageFormatter fmtr) {
		TransactionProfiler profiler = TransactionProfiler.getLocalProfiler();
		// Only the txs acquiring to append the block on the same file will be blocked
		profiler.startComponentProfiler("Prepare Anchor in BufferPoolMgr PinNew");
		synchronized (prepareAnchor(fileName)) {
			profiler.stopComponentProfiler("Prepare Anchor in BufferPoolMgr PinNew");
			// Choose Unpinned Buffer
			int lastReplacedBuff = this.lastReplacedBuff;
			int currBlk = (lastReplacedBuff + 1) % bufferPool.length;
			while (currBlk != lastReplacedBuff) {
				Buffer buff = bufferPool[currBlk];
				
				// OPTIMIZATION: Swap non-index buffers
				if (buff.block() == null || !buff.block().fileName().contains(".idx")) {
					// Get the lock of buffer if it is free
					profiler.startComponentProfiler("Buffer External Lock");
					if (buff.getExternalLock().tryLock()) {
						profiler.stopComponentProfiler("Buffer External Lock");
						try {
							if (!buff.isPinned() && !buff.checkRecentlyPinnedAndReset()) {
								this.lastReplacedBuff = currBlk;
								
								// Swap
								BlockId oldBlk = buff.block();
								if (oldBlk != null)
									blockMap.remove(oldBlk);
								buff.assignToNew(fileName, fmtr);
								blockMap.put(buff.block(), buff);
								if (!buff.isPinned())
									numAvailable.decrementAndGet();
								
								// Pin this buffer
								buff.pin();
								return buff;
							}
						} finally {
							// Release the lock of buffer
							buff.getExternalLock().unlock();
						}
					}
				}
				currBlk = (currBlk + 1) % bufferPool.length;
			}
			return null;
		}
	}

	/**
	 * Unpins the specified buffers.
	 * 
	 * @param buffs
	 *            the buffers to be unpinned
	 */
	void unpin(Buffer... buffs) {
		TransactionProfiler profiler = TransactionProfiler.getLocalProfiler();
		for (Buffer buff : buffs) {
			// OPTIMIZATION: Ignore those idx buffers
			if (buff.block() != null && buff.block().fileName().contains(".idx")) {
				continue;
			}
			try {
				// Get the lock of buffer
				profiler.startComponentProfiler("Buffer External Lock");
//				int num = ((ReentrantLock)buff.getExternalLock()).getQueueLength();
//				if (num > 0) {
//					System.out.println(num);
//				}
				buff.getExternalLock().lock();
				profiler.stopComponentProfiler("Buffer External Lock");
				buff.unpin();
				if (!buff.isPinned())
					numAvailable.incrementAndGet();
			} finally {
				// Release the lock of buffer
				buff.getExternalLock().unlock();
			}
		}
	}

	/**
	 * Returns the number of available (i.e. unpinned) buffers.
	 * 
	 * @return the number of available buffers
	 */
	int available() {
		return numAvailable.get();
	}

	private Buffer findExistingBuffer(BlockId blk) {
		Buffer buff = blockMap.get(blk);
		if (buff != null && buff.block().equals(blk))
			return buff;
		return null;
	}
	
	Buffer[] buffers() {
		return bufferPool;
	}
	
	double hitRate() {
		int miss = missCount.getAndSet(0);
		int total = totalCount.getAndSet(0);
		if (total == 0)
			return 1.0;
		else
			return (1 - ((double) miss) / ((double) total));
	}
}
