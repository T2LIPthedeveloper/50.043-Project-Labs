package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.Permissions;
import simpledb.common.DbException;
import simpledb.common.DeadlockException;
import simpledb.transaction.LockManager;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking; when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;

    /**
     * Default number of pages passed to the constructor. This is used by
     * other classes. BufferPool should use the numPages argument to the
     * constructor instead.
     */
    public static final int DEFAULT_PAGES = 50;

    private int numPages;

    private ConcurrentHashMap<PageId, Page> pages;

    private final LockManager lockManager;

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        this.lockManager = new LockManager();
        this.numPages = numPages;
        this.pages = new ConcurrentHashMap<>(numPages);
    }

    public static int getPageSize() {
        return pageSize;
    }

    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
        BufferPool.pageSize = pageSize;
    }

    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
        BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool. If it
     * is present, it should be returned. If it is not present, it should
     * be added to the buffer pool and returned. If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid  the ID of the transaction requesting the page
     * @param pid  the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
            throws TransactionAbortedException, DbException {
        if (perm == Permissions.READ_WRITE) { // dependent on lock manager
            this.lockManager.acquireWriteLock(tid, pid);
        } else if (perm == Permissions.READ_ONLY) { // dependent on lock manager
            this.lockManager.acquireReadLock(tid, pid);
        } else {
            throw new DbException("Permission requested is not valid.");
        }

        synchronized (this) {
            // Check if the requested page is in pages
            if (this.pages.containsKey(pid)) {
                Page page = this.pages.get(pid);
                this.pages.remove(pid);
                this.pages.put(pid, page);
                return page;
            }

            Page n_page = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);

            if (this.numPages <= this.pages.size()) {
                this.evictPage();
            }

            if (perm == Permissions.READ_WRITE) {
                n_page.markDirty(true, tid);
            }

            this.pages.put(pid, n_page);
            return n_page;
        }
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public void unsafeReleasePage(TransactionId tid, PageId pid) {
        // some code goes here
        this.lockManager.releaseLock(tid, pid);        
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) {
        // some code goes here
        this.transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        return this.lockManager.holdsLock(tid, p);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid    the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit) {
        // some code goes here
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid. Will
     * acquire a write lock on the page the tuple is added to and any other
     * pages that are updated (Lock acquisition is not needed for lab2).
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have
     * been dirtied to the cache (replacing any existing versions of those pages) so
     * that future requests see up-to-date pages.
     *
     * @param tid     the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t       the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        ArrayList<Page> pageArray = (ArrayList<Page>) file.insertTuple(tid, t);
        for (Page pg : pageArray) {
            pg.markDirty(true, tid);
            if (!this.pages.containsKey(pg.getId()) && this.pages.size() >= this.numPages) {
                this.evictPage();
            }
            // Cleverly re-use the same buffer pool instead of using another variable to
            // track the
            // LRU cache
            this.pages.remove(pg.getId());
            // Assign id to the page
            this.pages.put(pg.getId(), pg);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have
     * been dirtied to the cache (replacing any existing versions of those pages) so
     * that future requests see up-to-date pages.
     *
     * @param tid the transaction deleting the tuple.
     * @param t   the tuple to delete
     */
    public void deleteTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        int tableId = t.getRecordId().getPageId().getTableId();
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        ArrayList<Page> pageArray = (ArrayList<Page>) file.deleteTuple(tid, t);

        for (Page pg : pageArray) {
            pg.markDirty(true, tid);
            if (!this.pages.containsKey(pg.getId()) && this.pages.size() >= this.numPages) {
                this.evictPage();
            }
            // Cleverly re-use the same buffer pool instead of using another variable to
            // track the
            // LRU cache
            this.pages.remove(pg.getId());
            // Assign id to the page
            this.pages.put(pg.getId(), pg);
        }
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     * break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        for (Page p: this.pages.values()) {
            this.flushPage(p.getId());
        }
    }

    /**
     * Remove the specific page id from the buffer pool.
     * Needed by the recovery manager to ensure that the
     * buffer pool doesn't keep a rolled back page in its
     * cache.
     * 
     * Also used by B+ tree files to ensure that deleted pages
     * are removed from the cache so they can be reused safely
     */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
        // remove page from buffer pool without flushing to disk
        this.pages.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * 
     * @param pid an ID indicating the page to flush
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        Page pg = this.pages.get(pid);

        if (this.pages.containsKey(pid)) {
            TransactionId dirty = pg.isDirty();
            if (dirty != null) {
                Database.getLogFile().logWrite(dirty, pg.getBeforeImage(), pg);
                Database.getLogFile().force();
                DbFile hpFile = Database.getCatalog().getDatabaseFile(pid.getTableId());
                hpFile.writePage(pg);
                pg.markDirty(false, null);
            }
        }
    }

    /**
     * Write all pages of the specified transaction to disk.
     */
    public synchronized void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        if (this.lockManager.getPagesHeldBy(tid) == null) {
            return;
        }

        for (PageId pid : this.lockManager.getPagesHeldBy(tid)) {
            this.flushPage(pid);
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized void evictPage() throws DbException {
        // some code goes here
        Iterator<PageId> iter = this.pages.keySet().iterator();

        Page lruPage = null;

        while (iter.hasNext()) {
            Page page = this.pages.get(iter.next());
            if (page.isDirty() == null) {
                lruPage = page;
                break;
            }
        }

        if (lruPage == null) {
            throw new DbException("There are no pages to evict.");
        }

        try {
            this.flushPage(lruPage.getId());
        } catch (IOException e) {
            throw new DbException("Page could not be flushed.");
        }
        this.pages.remove(lruPage.getId());
    }

}
