package com.test.demo6_semaphore;

import sun.misc.Unsafe;

import javax.swing.text.Segment;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;


public class MyConcurrentHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V>, Serializable {



    /*
     * Overview:
     *
     * The primary design goal of this hash table is to maintain
     * concurrent readability (typically method get(), but also
     * iterators and related methods) while minimizing update
     * contention. Secondary goals are to keep space consumption about
     * the same or better than java.util.HashMap, and to support high
     * initial insertion rates on an empty table by many threads.
     *
     * This map usually acts as a binned (bucketed) hash table.  Each
     * key-value mapping is held in a Node.  Most nodes are instances
     * of the basic Node class with hash, key, value, and next
     * fields. However, various subclasses exist: TreeNodes are
     * arranged in balanced trees, not lists.  TreeBins hold the roots
     * of sets of TreeNodes. ForwardingNodes are placed at the heads
     * of bins during resizing. ReservationNodes are used as
     * placeholders while establishing values in computeIfAbsent and
     * related methods.  The types TreeBin, ForwardingNode, and
     * ReservationNode do not hold normal user keys, values, or
     * hashes, and are readily distinguishable during search etc
     * because they have negative hash fields and null key and value
     * fields. (These special nodes are either uncommon or transient,
     * so the impact of carrying around some unused fields is
     * insignificant.)
     *
     * The table is lazily initialized to a power-of-two size upon the
     * first insertion.  Each bin in the table normally contains a
     * list of Nodes (most often, the list has only zero or one Node).
     * Table accesses require volatile/atomic reads, writes, and
     * CASes.  Because there is no other way to arrange this without
     * adding further indirections, we use intrinsics
     * (sun.misc.Unsafe) operations.
     *
     * We use the top (sign) bit of Node hash fields for control
     * purposes -- it is available anyway because of addressing
     * constraints.  Nodes with negative hash fields are specially
     * handled or ignored in map methods.
     *
     * Insertion (via put or its variants) of the first node in an
     * empty bin is performed by just CASing it to the bin.  This is
     * by far the most common case for put operations under most
     * key/hash distributions.  Other update operations (insert,
     * delete, and replace) require locks.  We do not want to waste
     * the space required to associate a distinct lock object with
     * each bin, so instead use the first node of a bin list itself as
     * a lock. Locking support for these locks relies on builtin
     * "synchronized" monitors.
     *
     * Using the first node of a list as a lock does not by itself
     * suffice though: When a node is locked, any update must first
     * validate that it is still the first node after locking it, and
     * retry if not. Because new nodes are always appended to lists,
     * once a node is first in a bin, it remains first until deleted
     * or the bin becomes invalidated (upon resizing).
     *
     * The main disadvantage of per-bin locks is that other update
     * operations on other nodes in a bin list protected by the same
     * lock can stall, for example when user equals() or mapping
     * functions take a long time.  However, statistically, under
     * random hash codes, this is not a common problem.  Ideally, the
     * frequency of nodes in bins follows a Poisson distribution
     * (http://en.wikipedia.org/wiki/Poisson_distribution) with a
     * parameter of about 0.5 on average, given the resizing threshold
     * of 0.75, although with a large variance because of resizing
     * granularity. Ignoring variance, the expected occurrences of
     * list size k are (exp(-0.5) * pow(0.5, k) / factorial(k)). The
     * first values are:
     *
     * 0:    0.60653066
     * 1:    0.30326533
     * 2:    0.07581633
     * 3:    0.01263606
     * 4:    0.00157952
     * 5:    0.00015795
     * 6:    0.00001316
     * 7:    0.00000094
     * 8:    0.00000006
     * more: less than 1 in ten million
     *
     * Lock contention probability for two threads accessing distinct
     * elements is roughly 1 / (8 * #elements) under random hashes.
     *
     * Actual hash code distributions encountered in practice
     * sometimes deviate significantly from uniform randomness.  This
     * includes the case when N > (1<<30), so some keys MUST collide.
     * Similarly for dumb or hostile usages in which multiple keys are
     * designed to have identical hash codes or ones that differs only
     * in masked-out high bits. So we use a secondary strategy that
     * applies when the number of nodes in a bin exceeds a
     * threshold. These TreeBins use a balanced tree to hold nodes (a
     * specialized form of red-black trees), bounding search time to
     * O(log N).  Each search step in a TreeBin is at least twice as
     * slow as in a regular list, but given that N cannot exceed
     * (1<<64) (before running out of addresses) this bounds search
     * steps, lock hold times, etc, to reasonable constants (roughly
     * 100 nodes inspected per operation worst case) so long as keys
     * are Comparable (which is very common -- String, Long, etc).
     * TreeBin nodes (TreeNodes) also maintain the same "next"
     * traversal pointers as regular nodes, so can be traversed in
     * iterators in the same way.
     *
     * The table is resized when occupancy exceeds a percentage
     * threshold (nominally, 0.75, but see below).  Any thread
     * noticing an overfull bin may assist in resizing after the
     * initiating thread allocates and sets up the replacement array.
     * However, rather than stalling, these other threads may proceed
     * with insertions etc.  The use of TreeBins shields us from the
     * worst case effects of overfilling while resizes are in
     * progress.  Resizing proceeds by transferring bins, one by one,
     * from the table to the next table. However, threads claim small
     * blocks of indices to transfer (via field transferIndex) before
     * doing so, reducing contention.  A generation stamp in field
     * sizeCtl ensures that resizings do not overlap. Because we are
     * using power-of-two expansion, the elements from each bin must
     * either stay at same index, or move with a power of two
     * offset. We eliminate unnecessary node creation by catching
     * cases where old nodes can be reused because their next fields
     * won't change.  On average, only about one-sixth of them need
     * cloning when a table doubles. The nodes they replace will be
     * garbage collectable as soon as they are no longer referenced by
     * any reader thread that may be in the midst of concurrently
     * traversing table.  Upon transfer, the old table bin contains
     * only a special forwarding node (with hash field "MOVED") that
     * contains the next table as its key. On encountering a
     * forwarding node, access and update operations restart, using
     * the new table.
     *
     * Each bin transfer requires its bin lock, which can stall
     * waiting for locks while resizing. However, because other
     * threads can join in and help resize rather than contend for
     * locks, average aggregate waits become shorter as resizing
     * progresses.  The transfer operation must also ensure that all
     * accessible bins in both the old and new table are usable by any
     * traversal.  This is arranged in part by proceeding from the
     * last bin (table.length - 1) up towards the first.  Upon seeing
     * a forwarding node, traversals (see class Traverser) arrange to
     * move to the new table without revisiting nodes.  To ensure that
     * no intervening nodes are skipped even when moved out of order,
     * a stack (see class TableStack) is created on first encounter of
     * a forwarding node during a traversal, to maintain its place if
     * later processing the current table. The need for these
     * save/restore mechanics is relatively rare, but when one
     * forwarding node is encountered, typically many more will be.
     * So Traversers use a simple caching scheme to avoid creating so
     * many new TableStack nodes. (Thanks to Peter Levart for
     * suggesting use of a stack here.)
     *
     * The traversal scheme also applies to partial traversals of
     * ranges of bins (via an alternate Traverser constructor)
     * to support partitioned aggregate operations.  Also, read-only
     * operations give up if ever forwarded to a null table, which
     * provides support for shutdown-style clearing, which is also not
     * currently implemented.
     *
     * Lazy table initialization minimizes footprint until first use,
     * and also avoids resizings when the first operation is from a
     * putAll, constructor with map argument, or deserialization.
     * These cases attempt to override the initial capacity settings,
     * but harmlessly fail to take effect in cases of races.
     *
     * The element count is maintained using a specialization of
     * LongAdder. We need to incorporate a specialization rather than
     * just use a LongAdder in order to access implicit
     * contention-sensing that leads to creation of multiple
     * CounterCells.  The counter mechanics avoid contention on
     * updates but can encounter cache thrashing if read too
     * frequently during concurrent access. To avoid reading so often,
     * resizing under contention is attempted only upon adding to a
     * bin already holding two or more nodes. Under uniform hash
     * distributions, the probability of this occurring at threshold
     * is around 13%, meaning that only about 1 in 8 puts check
     * threshold (and after resizing, many fewer do so).
     *
     * TreeBins use a special form of comparison for search and
     * related operations (which is the main reason we cannot use
     * existing collections such as TreeMaps). TreeBins contain
     * Comparable elements, but may contain others, as well as
     * elements that are Comparable but not necessarily Comparable for
     * the same T, so we cannot invoke compareTo among them. To handle
     * this, the tree is ordered primarily by hash value, then by
     * Comparable.compareTo order if applicable.  On lookup at a node,
     * if elements are not comparable or compare as 0 then both left
     * and right children may need to be searched in the case of tied
     * hash values. (This corresponds to the full list search that
     * would be necessary if all elements were non-Comparable and had
     * tied hashes.) On insertion, to keep a total ordering (or as
     * close as is required here) across rebalancings, we compare
     * classes and identityHashCodes as tie-breakers. The red-black
     * balancing code is updated from pre-jdk-collections
     * (http://gee.cs.oswego.edu/dl/classes/collections/RBCell.java)
     * based in turn on Cormen, Leiserson, and Rivest "Introduction to
     * Algorithms" (CLR).
     *
     * TreeBins also require an additional locking mechanism.  While
     * list traversal is always possible by readers even during
     * updates, tree traversal is not, mainly because of tree-rotations
     * that may change the root node and/or its linkages.  TreeBins
     * include a simple read-write lock mechanism parasitic on the
     * main bin-synchronization strategy: Structural adjustments
     * associated with an insertion or removal are already bin-locked
     * (and so cannot conflict with other writers) but must wait for
     * ongoing readers to finish. Since there can be only one such
     * waiter, we use a simple scheme using a single "waiter" field to
     * block writers.  However, readers need never block.  If the root
     * lock is held, they proceed along the slow traversal path (via
     * next-pointers) until the lock becomes available or the list is
     * exhausted, whichever comes first. These cases are not fast, but
     * maximize aggregate expected throughput.
     *
     * Maintaining API and serialization compatibility with previous
     * versions of this class introduces several oddities. Mainly: We
     * leave untouched but unused constructor arguments refering to
     * concurrencyLevel. We accept a loadFactor constructor argument,
     * but apply it only to initial table capacity (which is the only
     * time that we can guarantee to honor it.) We also declare an
     * unused "Segment" class that is instantiated in minimal form
     * only when serializing.
     *
     * Also, solely for compatibility with previous versions of this
     * class, it extends AbstractMap, even though all of its methods
     * are overridden, so it is just useless baggage.
     *
     * This file is organized to make things a little easier to follow
     * while reading than they might otherwise: First the main static
     * declarations and utilities, then fields, then main public
     * methods (with a few factorings of multiple public methods into
     * internal ones), then sizing methods, trees, traversers, and
     * bulk operations.
     */

    /* ---------------- Constants -------------- */

    /**
     * The largest possible table capacity.  This value must be
     * exactly 1<<30 to stay within Java array allocation and indexing
     * bounds for power of two table sizes, and is further required
     * because the top two bits of 32bit hash fields are used for
     * control purposes.
     */
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * The default initial table capacity.  Must be a power of 2
     * (i.e., at least 1) and at most MAXIMUM_CAPACITY.
     */
    private static final int DEFAULT_CAPACITY = 16;

    /**
     * The largest possible (non-power of two) array size.
     * Needed by toArray and related methods.
     */
    static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * The default concurrency level for this table. Unused but
     * defined for compatibility with previous versions of this class.
     */
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    /**
     * The load factor for this table. Overrides of this value in
     * constructors affect only the initial table capacity.  The
     * actual floating point value isn't normally used -- it is
     * simpler to use expressions such as {@code n - (n >>> 2)} for
     * the associated resizing threshold.
     */
    private static final float LOAD_FACTOR = 0.75f;

    /**
     * The bin count threshold for using a tree rather than list for a
     * bin.  Bins are converted to trees when adding an element to a
     * bin with at least this many nodes. The value must be greater
     * than 2, and should be at least 8 to mesh with assumptions in
     * tree removal about conversion back to plain bins upon
     * shrinkage.
     */
    static final int TREEIFY_THRESHOLD = 8;

    /**
     * The bin count threshold for untreeifying a (split) bin during a
     * resize operation. Should be less than TREEIFY_THRESHOLD, and at
     * most 6 to mesh with shrinkage detection under removal.
     */
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * The smallest table capacity for which bins may be treeified.
     * (Otherwise the table is resized if too many nodes in a bin.)
     * The value should be at least 4 * TREEIFY_THRESHOLD to avoid
     * conflicts between resizing and treeification thresholds.
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    /**
     * Minimum number of rebinnings per transfer step. Ranges are
     * subdivided to allow multiple resizer threads.  This value
     * serves as a lower bound to avoid resizers encountering
     * excessive memory contention.  The value should be at least
     * DEFAULT_CAPACITY.
     */
    private static final int MIN_TRANSFER_STRIDE = 16;

    /**
     * The number of bits used for generation stamp in sizeCtl.
     * Must be at least 6 for 32bit arrays.
     */
    private static int RESIZE_STAMP_BITS = 16;

    /**
     * The maximum number of threads that can help resize.
     * Must fit in 32 - RESIZE_STAMP_BITS bits.
     */
    private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1;

    /**
     * The bit shift for recording size stamp in sizeCtl.
     */
    private static final int RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS;

    /*
     * Encodings for Node hash fields. See above for explanation.
     */
    static final int MOVED = -1; // hash for forwarding nodes
    static final int TREEBIN = -2; // hash for roots of trees
    static final int RESERVED = -3; // hash for transient reservations
    static final int HASH_BITS = 0x7fffffff; // usable bits of normal node hash

    /**
     * Number of CPUS, to place bounds on some sizings
     */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * For serialization compatibility.
     */
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("segments", Segment[].class),
            new ObjectStreamField("segmentMask", Integer.TYPE),
            new ObjectStreamField("segmentShift", Integer.TYPE)
    };


    /* ---------------- Fields -------------- */

    /**
     * The array of bins. Lazily initialized upon first insertion.
     * Size is always a power of two. Accessed directly by iterators.
     */
    transient volatile MyConcurrentHashMap.Node<K, V>[] table;

    /**
     * The next table to use; non-null only while resizing.
     */
    private transient volatile MyConcurrentHashMap.Node<K, V>[] nextTable;

    /**
     * Base counter value, used mainly when there is no contention,
     * but also as a fallback during table initialization
     * races. Updated via CAS.
     */
    private transient volatile long baseCount;

    /**
     * Table initialization and resizing control.  When negative, the
     * table is being initialized or resized: -1 for initialization,
     * else -(1 + the number of active resizing threads).  Otherwise,
     * when table is null, holds the initial table size to use upon
     * creation, or 0 for default. After initialization, holds the
     * next element count value upon which to resize the table.
     */
    private transient volatile int sizeCtl;

    /**
     * The next table index (plus one) to split while resizing.
     */
    private transient volatile int transferIndex;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating CounterCells.
     */
    private transient volatile int cellsBusy;

    /**
     * Table of counter cells. When non-null, size is a power of 2.
     */
    private transient volatile MyConcurrentHashMap.CounterCell[] counterCells;


    /**
     * Key-value entry.  This class is never exported out as a
     * user-mutable(可变的) Map.Entry (i.e., one supporting setValue; see
     * MapEntry below(在下面)), but can be used for read-only traversals(遍历) used
     * in bulk(大量的) tasks.  Subclasses(子类) of Node with a negative hash field
     * are special, and contain null keys and values (but are never
     * exported(出口)).  Otherwise, keys and vals are never null.
     */
    static class Node<K, V> implements Map.Entry<K, V> {
        final int hash;
        final K key;
        volatile V val;
        volatile MyConcurrentHashMap.Node<K, V> next;

        Node(int hash, K key, V val, MyConcurrentHashMap.Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.val = val;
            this.next = next;
        }

        public final K getKey() {
            return key;
        }

        public final V getValue() {
            return val;
        }

        public final int hashCode() {
            return key.hashCode() ^ val.hashCode();
        }

        public final String toString() {
            return key + "=" + val;
        }

        public final V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        public final boolean equals(Object o) {
            Object k, v, u;
            Map.Entry<?, ?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?, ?>) o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == (u = val) || v.equals(u)));
        }

        /**
         * Virtualized support for map.get(); overridden in subclasses.
         */
        MyConcurrentHashMap.Node<K, V> find(int h, Object k) {
            MyConcurrentHashMap.Node<K, V> e = this;
            if (k != null) {
                do {
                    K ek;
                    if (e.hash == h &&
                            ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                } while ((e = e.next) != null);
            }
            return null;
        }
    }


    /* ---------------- TreeNodes -------------- */

    /**
     * Nodes for use in TreeBins
     */
    static final class TreeNode<K, V> extends MyConcurrentHashMap.Node<K, V> {
        MyConcurrentHashMap.TreeNode<K, V> parent;  // red-black tree links
        MyConcurrentHashMap.TreeNode<K, V> left;
        MyConcurrentHashMap.TreeNode<K, V> right;
        MyConcurrentHashMap.TreeNode<K, V> prev;    // needed to unlink next upon deletion
        boolean red;

        TreeNode(int hash, K key, V val, MyConcurrentHashMap.Node<K, V> next,
                 MyConcurrentHashMap.TreeNode<K, V> parent) {
            super(hash, key, val, next);
            this.parent = parent;
        }

        MyConcurrentHashMap.Node<K, V> find(int h, Object k) {
            return findTreeNode(h, k, null);
        }

        /**
         * Returns the TreeNode (or null if not found) for the given key
         * starting at given root.
         */
        final MyConcurrentHashMap.TreeNode<K, V> findTreeNode(int h, Object k, Class<?> kc) {
            if (k != null) {
                MyConcurrentHashMap.TreeNode<K, V> p = this;
                do {
                    int ph, dir;
                    K pk;
                    MyConcurrentHashMap.TreeNode<K, V> q;
                    MyConcurrentHashMap.TreeNode<K, V> pl = p.left, pr = p.right;
                    if ((ph = p.hash) > h)
                        p = pl;
                    else if (ph < h)
                        p = pr;
                    else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                        return p;
                    else if (pl == null)
                        p = pr;
                    else if (pr == null)
                        p = pl;
                    else if ((kc != null ||
                            (kc = comparableClassFor(k)) != null) &&
                            (dir = compareComparables(kc, k, pk)) != 0)
                        p = (dir < 0) ? pl : pr;
                    else if ((q = pr.findTreeNode(h, k, kc)) != null)
                        return q;
                    else
                        p = pl;
                } while (p != null);
            }
            return null;
        }
    }


    /**
     * Returns k.compareTo(x) if x matches kc (k's screened comparable
     * class), else 0.
     */
    @SuppressWarnings({"rawtypes", "unchecked"}) // for cast to Comparable
    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 :
                ((Comparable) k).compareTo(x));
    }


    /**
     * Returns x's Class if it is of the form "class C implements
     * Comparable<C>", else null.
     */
    static Class<?> comparableClassFor(Object x) {
        if (x instanceof Comparable) {
            Class<?> c;
            Type[] ts, as;
            Type t;
            ParameterizedType p;
            if ((c = x.getClass()) == String.class) // bypass checks
                return c;
            if ((ts = c.getGenericInterfaces()) != null) {
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                            ((p = (ParameterizedType) t).getRawType() ==
                                    Comparable.class) &&
                            (as = p.getActualTypeArguments()) != null &&
                            as.length == 1 && as[0] == c) // type arg is c
                        return c;
                }
            }
        }
        return null;
    }




    /* ---------------- TreeBins -------------- */

    /**
     * TreeNodes used at the heads of bins. TreeBins do not hold user
     * keys or values, but instead point to list of TreeNodes and
     * their root. They also maintain a parasitic read-write lock
     * forcing writers (who hold bin lock) to wait for readers (who do
     * not) to complete before tree restructuring operations.
     */
    static final class TreeBin<K, V> extends MyConcurrentHashMap.Node<K, V> {
        MyConcurrentHashMap.TreeNode<K, V> root;
        volatile MyConcurrentHashMap.TreeNode<K, V> first;
        volatile Thread waiter;
        volatile int lockState;
        // values for lockState
        static final int WRITER = 1; // set while holding write lock
        static final int WAITER = 2; // set when waiting for write lock
        static final int READER = 4; // increment value for setting read lock

        /**
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                    (d = a.getClass().getName().
                            compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                        -1 : 1);
            return d;
        }

        /**
         * Creates bin with initial set of nodes headed by b.
         */
        TreeBin(MyConcurrentHashMap.TreeNode<K, V> b) {
            super(TREEBIN, null, null, null);
            this.first = b;
            MyConcurrentHashMap.TreeNode<K, V> r = null;
            for (MyConcurrentHashMap.TreeNode<K, V> x = b, next; x != null; x = next) {
                next = (MyConcurrentHashMap.TreeNode<K, V>) x.next;
                x.left = x.right = null;
                if (r == null) {
                    x.parent = null;
                    x.red = false;
                    r = x;
                } else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (MyConcurrentHashMap.TreeNode<K, V> p = r; ; ) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1;
                        else if (ph < h)
                            dir = 1;
                        else if ((kc == null &&
                                (kc = comparableClassFor(k)) == null) ||
                                (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);
                        MyConcurrentHashMap.TreeNode<K, V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            r = balanceInsertion(r, x);
                            break;
                        }
                    }
                }
            }
            this.root = r;
            assert checkInvariants(root);
        }

        /**
         * Acquires write lock for tree restructuring.
         */
        private final void lockRoot() {
            if (!U.compareAndSwapInt(this, LOCKSTATE, 0, WRITER))
                contendedLock(); // offload to separate method
        }

        /**
         * Releases write lock for tree restructuring.
         */
        private final void unlockRoot() {
            lockState = 0;
        }

        /**
         * Possibly blocks awaiting root lock.
         */
        private final void contendedLock() {
            boolean waiting = false;
            for (int s; ; ) {
                if (((s = lockState) & ~WAITER) == 0) {
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, WRITER)) {
                        if (waiting)
                            waiter = null;
                        return;
                    }
                } else if ((s & WAITER) == 0) {
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, s | WAITER)) {
                        waiting = true;
                        waiter = Thread.currentThread();
                    }
                } else if (waiting)
                    LockSupport.park(this);
            }
        }

        /**
         * Returns matching node or null if none. Tries to search
         * using tree comparisons from root, but continues linear
         * search when lock not available.
         */
        final MyConcurrentHashMap.Node<K, V> find(int h, Object k) {
            if (k != null) {
                for (MyConcurrentHashMap.Node<K, V> e = first; e != null; ) {
                    int s;
                    K ek;
                    if (((s = lockState) & (WAITER | WRITER)) != 0) {
                        if (e.hash == h &&
                                ((ek = e.key) == k || (ek != null && k.equals(ek))))
                            return e;
                        e = e.next;
                    } else if (U.compareAndSwapInt(this, LOCKSTATE, s,
                            s + READER)) {
                        MyConcurrentHashMap.TreeNode<K, V> r, p;
                        try {
                            p = ((r = root) == null ? null :
                                    r.findTreeNode(h, k, null));
                        } finally {
                            Thread w;
                            if (U.getAndAddInt(this, LOCKSTATE, -READER) ==
                                    (READER | WAITER) && (w = waiter) != null)
                                LockSupport.unpark(w);
                        }
                        return p;
                    }
                }
            }
            return null;
        }

        /**
         * Finds or adds a node.
         *
         * @return null if added
         */
        final MyConcurrentHashMap.TreeNode<K, V> putTreeVal(int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            for (MyConcurrentHashMap.TreeNode<K, V> p = root; ; ) {
                int dir, ph;
                K pk;
                if (p == null) {
                    first = root = new MyConcurrentHashMap.TreeNode<K, V>(h, k, v, null, null);
                    break;
                } else if ((ph = p.hash) > h)
                    dir = -1;
                else if (ph < h)
                    dir = 1;
                else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                    return p;
                else if ((kc == null &&
                        (kc = comparableClassFor(k)) == null) ||
                        (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        MyConcurrentHashMap.TreeNode<K, V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                                (q = ch.findTreeNode(h, k, kc)) != null) ||
                                ((ch = p.right) != null &&
                                        (q = ch.findTreeNode(h, k, kc)) != null))
                            return q;
                    }
                    dir = tieBreakOrder(k, pk);
                }

                MyConcurrentHashMap.TreeNode<K, V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    MyConcurrentHashMap.TreeNode<K, V> x, f = first;
                    first = x = new MyConcurrentHashMap.TreeNode<K, V>(h, k, v, f, xp);
                    if (f != null)
                        f.prev = x;
                    if (dir <= 0)
                        xp.left = x;
                    else
                        xp.right = x;
                    if (!xp.red)
                        x.red = true;
                    else {
                        lockRoot();
                        try {
                            root = balanceInsertion(root, x);
                        } finally {
                            unlockRoot();
                        }
                    }
                    break;
                }
            }
            assert checkInvariants(root);
            return null;
        }

        /**
         * Removes the given node, that must be present before this
         * call.  This is messier than typical red-black deletion code
         * because we cannot swap the contents of an interior node
         * with a leaf successor that is pinned by "next" pointers
         * that are accessible independently of lock. So instead we
         * swap the tree linkages.
         *
         * @return true if now too small, so should be untreeified
         */
        final boolean removeTreeNode(MyConcurrentHashMap.TreeNode<K, V> p) {
            MyConcurrentHashMap.TreeNode<K, V> next = (MyConcurrentHashMap.TreeNode<K, V>) p.next;
            MyConcurrentHashMap.TreeNode<K, V> pred = p.prev;  // unlink traversal pointers
            MyConcurrentHashMap.TreeNode<K, V> r, rl;
            if (pred == null)
                first = next;
            else
                pred.next = next;
            if (next != null)
                next.prev = pred;
            if (first == null) {
                root = null;
                return true;
            }
            if ((r = root) == null || r.right == null || // too small
                    (rl = r.left) == null || rl.left == null)
                return true;
            lockRoot();
            try {
                MyConcurrentHashMap.TreeNode<K, V> replacement;
                MyConcurrentHashMap.TreeNode<K, V> pl = p.left;
                MyConcurrentHashMap.TreeNode<K, V> pr = p.right;
                if (pl != null && pr != null) {
                    MyConcurrentHashMap.TreeNode<K, V> s = pr, sl;
                    while ((sl = s.left) != null) // find successor
                        s = sl;
                    boolean c = s.red;
                    s.red = p.red;
                    p.red = c; // swap colors
                    MyConcurrentHashMap.TreeNode<K, V> sr = s.right;
                    MyConcurrentHashMap.TreeNode<K, V> pp = p.parent;
                    if (s == pr) { // p was s's direct parent
                        p.parent = s;
                        s.right = p;
                    } else {
                        MyConcurrentHashMap.TreeNode<K, V> sp = s.parent;
                        if ((p.parent = sp) != null) {
                            if (s == sp.left)
                                sp.left = p;
                            else
                                sp.right = p;
                        }
                        if ((s.right = pr) != null)
                            pr.parent = s;
                    }
                    p.left = null;
                    if ((p.right = sr) != null)
                        sr.parent = p;
                    if ((s.left = pl) != null)
                        pl.parent = s;
                    if ((s.parent = pp) == null)
                        r = s;
                    else if (p == pp.left)
                        pp.left = s;
                    else
                        pp.right = s;
                    if (sr != null)
                        replacement = sr;
                    else
                        replacement = p;
                } else if (pl != null)
                    replacement = pl;
                else if (pr != null)
                    replacement = pr;
                else
                    replacement = p;
                if (replacement != p) {
                    MyConcurrentHashMap.TreeNode<K, V> pp = replacement.parent = p.parent;
                    if (pp == null)
                        r = replacement;
                    else if (p == pp.left)
                        pp.left = replacement;
                    else
                        pp.right = replacement;
                    p.left = p.right = p.parent = null;
                }

                root = (p.red) ? r : balanceDeletion(r, replacement);

                if (p == replacement) {  // detach pointers
                    MyConcurrentHashMap.TreeNode<K, V> pp;
                    if ((pp = p.parent) != null) {
                        if (p == pp.left)
                            pp.left = null;
                        else if (p == pp.right)
                            pp.right = null;
                        p.parent = null;
                    }
                }
            } finally {
                unlockRoot();
            }
            assert checkInvariants(root);
            return false;
        }

        /* ------------------------------------------------------------ */
        // Red-black tree methods, all adapted from CLR

        static <K, V> MyConcurrentHashMap.TreeNode<K, V> rotateLeft(MyConcurrentHashMap.TreeNode<K, V> root,
                                                                    MyConcurrentHashMap.TreeNode<K, V> p) {
            MyConcurrentHashMap.TreeNode<K, V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null)
                    rl.parent = p;
                if ((pp = r.parent = p.parent) == null)
                    (root = r).red = false;
                else if (pp.left == p)
                    pp.left = r;
                else
                    pp.right = r;
                r.left = p;
                p.parent = r;
            }
            return root;
        }

        static <K, V> MyConcurrentHashMap.TreeNode<K, V> rotateRight(MyConcurrentHashMap.TreeNode<K, V> root,
                                                                     MyConcurrentHashMap.TreeNode<K, V> p) {
            MyConcurrentHashMap.TreeNode<K, V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null)
                    lr.parent = p;
                if ((pp = l.parent = p.parent) == null)
                    (root = l).red = false;
                else if (pp.right == p)
                    pp.right = l;
                else
                    pp.left = l;
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        static <K, V> MyConcurrentHashMap.TreeNode<K, V> balanceInsertion(MyConcurrentHashMap.TreeNode<K, V> root,
                                                                          MyConcurrentHashMap.TreeNode<K, V> x) {
            x.red = true;
            for (MyConcurrentHashMap.TreeNode<K, V> xp, xpp, xppl, xppr; ; ) {
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (!xp.red || (xpp = xp.parent) == null)
                    return root;
                if (xp == (xppl = xpp.left)) {
                    if ((xppr = xpp.right) != null && xppr.red) {
                        xppr.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.right) {
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateRight(root, xpp);
                            }
                        }
                    }
                } else {
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        static <K, V> MyConcurrentHashMap.TreeNode<K, V> balanceDeletion(MyConcurrentHashMap.TreeNode<K, V> root,
                                                                         MyConcurrentHashMap.TreeNode<K, V> x) {
            for (MyConcurrentHashMap.TreeNode<K, V> xp, xpl, xpr; ; ) {
                if (x == null || x == root)
                    return root;
                else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (x.red) {
                    x.red = false;
                    return root;
                } else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null)
                        x = xp;
                    else {
                        MyConcurrentHashMap.TreeNode<K, V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                                (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        } else {
                            if (sr == null || !sr.red) {
                                if (sl != null)
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                        null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                } else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null)
                        x = xp;
                    else {
                        MyConcurrentHashMap.TreeNode<K, V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                                (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        } else {
                            if (sl == null || !sl.red) {
                                if (sr != null)
                                    sr.red = false;
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                        null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null)
                                    sl.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }

        /**
         * Recursive invariant check
         */
        static <K, V> boolean checkInvariants(MyConcurrentHashMap.TreeNode<K, V> t) {
            MyConcurrentHashMap.TreeNode<K, V> tp = t.parent, tl = t.left, tr = t.right,
                    tb = t.prev, tn = (MyConcurrentHashMap.TreeNode<K, V>) t.next;
            if (tb != null && tb.next != t)
                return false;
            if (tn != null && tn.prev != t)
                return false;
            if (tp != null && t != tp.left && t != tp.right)
                return false;
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false;
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false;
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false;
            if (tl != null && !checkInvariants(tl))
                return false;
            if (tr != null && !checkInvariants(tr))
                return false;
            return true;
        }

        private static final sun.misc.Unsafe U;
        private static final long LOCKSTATE;

        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> k = MyConcurrentHashMap.TreeBin.class;
                LOCKSTATE = U.objectFieldOffset
                        (k.getDeclaredField("lockState"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }


    /**
     * {@inheritDoc}
     *
     * @return the previous(先前的) value associated（关联起来） with the specified(具体说明) key,
     * or {@code null} if there was no mapping for the key
     * @throws NullPointerException if the specified key or value is null
     */
    public V putIfAbsent(K key, V value) {
        return putVal(key, value, true);
    }

    /**
     * Spreads (XORs) higher bits of hash to lower and also forces top
     * bit to 0. Because the table uses power-of-two(二次幂) masking(掩蔽), sets of
     * hashes that vary(变化) only in bits above the current mask will
     * always collide(碰撞). (Among known examples(在已知的例子中) are sets of Float keys
     * holding consecutive(连续不断的) whole numbers in small(小的) tables.)  So we
     * apply a transform that spreads the impact(影响) of higher bits
     * downward(向下地). There is a tradeoff(折衷) between speed, utility(效用), and
     * quality of bit-spreading. Because many common sets of hashes
     * are already reasonably distributed(分布的) (so don't benefit from
     * spreading), and because we use trees to handle large sets of
     * collisions(碰撞) in bins, we just XOR some shifted bits in the
     * cheapest(便宜地) possible way to reduce systematic lossage(损失), as well as
     * to incorporate(合并) impact(影响) of the highest bits that would otherwise
     * never be used in index calculations because of table bounds(界限).
     *
     * 跟HashMap的hash算法类似，只是把位数控制在int最大整数之内。
     *
     */
    public static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }


    /**
     * Initializes table, using the size recorded in sizeCtl.
     */
    private final MyConcurrentHashMap.Node<K, V>[] initTable() {
        MyConcurrentHashMap.Node<K, V>[] tab;
        int sc;
        while ((tab = table) == null || tab.length == 0) {
            if ((sc = sizeCtl) < 0)
                Thread.yield(); // lost initialization race; just spin
            else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                try {
                    if ((tab = table) == null || tab.length == 0) {
                        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                        @SuppressWarnings("unchecked")
                        MyConcurrentHashMap.Node<K, V>[] nt = (MyConcurrentHashMap.Node<K, V>[]) new MyConcurrentHashMap.Node<?, ?>[n];
                        table = tab = nt;
                        sc = n - (n >>> 2);
                    }
                } finally {
                    sizeCtl = sc;
                }
                break;
            }
        }
        return tab;
    }

    /* ---------------- Table element access -------------- */

    /*
     * Volatile access methods are used for table elements as well as
     * elements of in-progress next table while resizing.  All uses of
     * the tab arguments must be null checked by callers.  All callers
     * also paranoically precheck that tab's length is not zero (or an
     * equivalent check), thus ensuring that any index argument taking
     * the form of a hash value anded with (length - 1) is a valid
     * index.  Note that, to be correct wrt arbitrary concurrency
     * errors by users, these checks must operate on local variables,
     * which accounts for some odd-looking inline assignments below.
     * Note that calls to setTabAt always occur within locked regions,
     * and so in principle require only release ordering, not
     * full volatile semantics, but are currently coded as volatile
     * writes to be conservative.
     */

    @SuppressWarnings("unchecked")
    static final <K, V> MyConcurrentHashMap.Node<K, V> tabAt(MyConcurrentHashMap.Node<K, V>[] tab, int i) {
        return (MyConcurrentHashMap.Node<K, V>) U.getObjectVolatile(tab, ((long) i << ASHIFT) + ABASE);
    }


    static final <K, V> boolean casTabAt(MyConcurrentHashMap.Node<K, V>[] tab, int i,
                                         MyConcurrentHashMap.Node<K, V> c, MyConcurrentHashMap.Node<K, V> v) {
        return U.compareAndSwapObject(tab, ((long) i << ASHIFT) + ABASE, c, v);
    }


    static final <K, V> void setTabAt(MyConcurrentHashMap.Node<K, V>[] tab, int i, MyConcurrentHashMap.Node<K, V> v) {
        U.putObjectVolatile(tab, ((long) i << ASHIFT) + ABASE, v);
    }



    /**
     * Maps the specified(具体说明) key to the specified value in this table.
     * Neither(都不) the key nor the value can be null.
     *
     * <p>The value can be retrieved(取回) by calling the {@code get} method
     * with a key that is equal to the original key.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous(先前的) value associated with {@code key}, or
     *         {@code null} if there was no mapping for {@code key}
     * @throws NullPointerException if the specified key or value is null
     */
    public V put(K key, V value) {
        return putVal(key, value, false);
    }



    /**
     * Implementation for put and putIfAbsent
     */
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        // 1.校验参数是否合法
        if (key == null || value == null) throw new NullPointerException();
        int hash = spread(key.hashCode());
        System.out.println("-----hash------" + hash);
        int binCount = 0;
        for (Node<K, V>[] tab = table; ; ) {
            Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                if (casTabAt(tab, i, null,
                        new Node<K, V>(hash, key, value, null)))
                    break;                   // no lock when adding to empty bin
            } else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                V oldVal = null;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K, V> e = f; ; ++binCount) {
                                K ek;
                                if (e.hash == hash &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    oldVal = e.val;
                                    if (!onlyIfAbsent)
                                        e.val = value;
                                    break;
                                }
                                Node<K, V> pred = e;
                                if ((e = e.next) == null) {
                                    pred.next = new Node<K, V>(hash, key,
                                            value, null);
                                    break;
                                }
                            }
                        } else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            Node<K, V> p;
                            binCount = 2;
                            if ((p = ((MyConcurrentHashMap.TreeBin<K, V>) f).putTreeVal(hash, key, value)) != null) {
                                oldVal = p.val;
                                if (!onlyIfAbsent)
                                    p.val = value;
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (oldVal != null)
                        return oldVal;
                    break;
                }
            }
        }
        addCount(1L, binCount);
        return null;
    }


    /**
     * Adds to count, and if table is too small and not already
     * resizing, initiates transfer. If already resizing, helps
     * perform transfer if work is available.  Rechecks occupancy
     * after a transfer to see if another resize is already needed
     * because resizings are lagging additions.
     *
     * @param x     the count to add
     * @param check if <0, don't check resize, if <= 1 only check if uncontended
     */
    private final void addCount(long x, int check) {
        MyConcurrentHashMap.CounterCell[] as;
        long b, s;
        if ((as = counterCells) != null ||
                !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
            MyConcurrentHashMap.CounterCell a;
            long v;
            int m;
            boolean uncontended = true;
            if (as == null || (m = as.length - 1) < 0 ||
                    (a = as[MyThreadLocalRandom.getProbe() & m]) == null ||
                    !(uncontended =
                            U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))) {
                fullAddCount(x, uncontended);
                return;
            }
            if (check <= 1)
                return;
            s = sumCount();
        }
        if (check >= 0) {
            MyConcurrentHashMap.Node<K, V>[] tab, nt;
            int n, sc;
            while (s >= (long) (sc = sizeCtl) && (tab = table) != null &&
                    (n = tab.length) < MAXIMUM_CAPACITY) {
                int rs = resizeStamp(n);
                if (sc < 0) {
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                            sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                            transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                } else if (U.compareAndSwapInt(this, SIZECTL, sc,
                        (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
                s = sumCount();
            }
        }
    }


    final long sumCount() {
        MyConcurrentHashMap.CounterCell[] as = counterCells;
        MyConcurrentHashMap.CounterCell a;
        long sum = baseCount;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }


    // See LongAdder version for explanation
    private final void fullAddCount(long x, boolean wasUncontended) {
        int h;
        if ((h = MyThreadLocalRandom.getProbe()) == 0) {
            MyThreadLocalRandom.localInit();      // force initialization
            h = MyThreadLocalRandom.getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (; ; ) {
            MyConcurrentHashMap.CounterCell[] as;
            MyConcurrentHashMap.CounterCell a;
            int n;
            long v;
            if ((as = counterCells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {            // Try to attach new Cell
                        MyConcurrentHashMap.CounterCell r = new MyConcurrentHashMap.CounterCell(x); // Optimistic create
                        if (cellsBusy == 0 &&
                                U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                            boolean created = false;
                            try {               // Recheck under lock
                                MyConcurrentHashMap.CounterCell[] rs;
                                int m, j;
                                if ((rs = counterCells) != null &&
                                        (m = rs.length) > 0 &&
                                        rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                } else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))
                    break;
                else if (counterCells != as || n >= NCPU)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 &&
                        U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                    try {
                        if (counterCells == as) {// Expand table unless stale
                            MyConcurrentHashMap.CounterCell[] rs = new MyConcurrentHashMap.CounterCell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            counterCells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = MyThreadLocalRandom.advanceProbe(h);
            } else if (cellsBusy == 0 && counterCells == as &&
                    U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                boolean init = false;
                try {                           // Initialize table
                    if (counterCells == as) {
                        MyConcurrentHashMap.CounterCell[] rs = new MyConcurrentHashMap.CounterCell[2];
                        rs[h & 1] = new MyConcurrentHashMap.CounterCell(x);
                        counterCells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            } else if (U.compareAndSwapLong(this, BASECOUNT, v = baseCount, v + x))
                break;                          // Fall back on using base
        }
    }



    /* ---------------- Conversion from/to TreeBins -------------- */

    /**
     * Replaces all linked nodes in bin at given index unless table is
     * too small, in which case resizes instead.
     */
    private final void treeifyBin(MyConcurrentHashMap.Node<K, V>[] tab, int index) {
        MyConcurrentHashMap.Node<K, V> b;
        int n, sc;
        if (tab != null) {
            if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
                tryPresize(n << 1);
            else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {
                synchronized (b) {
                    if (tabAt(tab, index) == b) {
                        MyConcurrentHashMap.TreeNode<K, V> hd = null, tl = null;
                        for (MyConcurrentHashMap.Node<K, V> e = b; e != null; e = e.next) {
                            MyConcurrentHashMap.TreeNode<K, V> p =
                                    new MyConcurrentHashMap.TreeNode<K, V>(e.hash, e.key, e.val,
                                            null, null);
                            if ((p.prev = tl) == null)
                                hd = p;
                            else
                                tl.next = p;
                            tl = p;
                        }
                        setTabAt(tab, index, new MyConcurrentHashMap.TreeBin<K, V>(hd));
                    }
                }
            }
        }
    }

    /**
     * Returns a power of two table size for the given desired capacity.
     * See Hackers Delight, sec 3.2
     */
    private static final int tableSizeFor(int c) {
        int n = c - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }


    /**
     * Tries to presize table to accommodate the given number of elements.
     *
     * @param size number of elements (doesn't need to be perfectly accurate)
     */
    private final void tryPresize(int size) {
        int c = (size >= (MAXIMUM_CAPACITY >>> 1)) ? MAXIMUM_CAPACITY :
                tableSizeFor(size + (size >>> 1) + 1);
        int sc;
        while ((sc = sizeCtl) >= 0) {
            MyConcurrentHashMap.Node<K, V>[] tab = table;
            int n;
            if (tab == null || (n = tab.length) == 0) {
                n = (sc > c) ? sc : c;
                if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                    try {
                        if (table == tab) {
                            @SuppressWarnings("unchecked")
                            MyConcurrentHashMap.Node<K, V>[] nt = (MyConcurrentHashMap.Node<K, V>[]) new MyConcurrentHashMap.Node<?, ?>[n];
                            table = nt;
                            sc = n - (n >>> 2);
                        }
                    } finally {
                        sizeCtl = sc;
                    }
                }
            } else if (c <= sc || n >= MAXIMUM_CAPACITY)
                break;
            else if (tab == table) {
                int rs = resizeStamp(n);
                if (sc < 0) {
                    MyConcurrentHashMap.Node<K, V>[] nt;
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                            sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                            transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                } else if (U.compareAndSwapInt(this, SIZECTL, sc,
                        (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
            }
        }
    }


    /**
     * Moves and/or copies the nodes in each bin to new table. See
     * above for explanation.
     */
    private final void transfer(MyConcurrentHashMap.Node<K, V>[] tab, MyConcurrentHashMap.Node<K, V>[] nextTab) {
        int n = tab.length, stride;
        if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
            stride = MIN_TRANSFER_STRIDE; // subdivide range
        if (nextTab == null) {            // initiating
            try {
                @SuppressWarnings("unchecked")
                MyConcurrentHashMap.Node<K, V>[] nt = (MyConcurrentHashMap.Node<K, V>[]) new MyConcurrentHashMap.Node<?, ?>[n << 1];
                nextTab = nt;
            } catch (Throwable ex) {      // try to cope with OOME
                sizeCtl = Integer.MAX_VALUE;
                return;
            }
            nextTable = nextTab;
            transferIndex = n;
        }
        int nextn = nextTab.length;
        MyConcurrentHashMap.ForwardingNode<K, V> fwd = new MyConcurrentHashMap.ForwardingNode<K, V>(nextTab);
        boolean advance = true;
        boolean finishing = false; // to ensure sweep before committing nextTab
        for (int i = 0, bound = 0; ; ) {
            MyConcurrentHashMap.Node<K, V> f;
            int fh;
            while (advance) {
                int nextIndex, nextBound;
                if (--i >= bound || finishing)
                    advance = false;
                else if ((nextIndex = transferIndex) <= 0) {
                    i = -1;
                    advance = false;
                } else if (U.compareAndSwapInt
                        (this, TRANSFERINDEX, nextIndex,
                                nextBound = (nextIndex > stride ?
                                        nextIndex - stride : 0))) {
                    bound = nextBound;
                    i = nextIndex - 1;
                    advance = false;
                }
            }
            if (i < 0 || i >= n || i + n >= nextn) {
                int sc;
                if (finishing) {
                    nextTable = null;
                    table = nextTab;
                    sizeCtl = (n << 1) - (n >>> 1);
                    return;
                }
                if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                        return;
                    finishing = advance = true;
                    i = n; // recheck before commit
                }
            } else if ((f = tabAt(tab, i)) == null)
                advance = casTabAt(tab, i, null, fwd);
            else if ((fh = f.hash) == MOVED)
                advance = true; // already processed
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        MyConcurrentHashMap.Node<K, V> ln, hn;
                        if (fh >= 0) {
                            int runBit = fh & n;
                            MyConcurrentHashMap.Node<K, V> lastRun = f;
                            for (MyConcurrentHashMap.Node<K, V> p = f.next; p != null; p = p.next) {
                                int b = p.hash & n;
                                if (b != runBit) {
                                    runBit = b;
                                    lastRun = p;
                                }
                            }
                            if (runBit == 0) {
                                ln = lastRun;
                                hn = null;
                            } else {
                                hn = lastRun;
                                ln = null;
                            }
                            for (MyConcurrentHashMap.Node<K, V> p = f; p != lastRun; p = p.next) {
                                int ph = p.hash;
                                K pk = p.key;
                                V pv = p.val;
                                if ((ph & n) == 0)
                                    ln = new MyConcurrentHashMap.Node<K, V>(ph, pk, pv, ln);
                                else
                                    hn = new MyConcurrentHashMap.Node<K, V>(ph, pk, pv, hn);
                            }
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        } else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            MyConcurrentHashMap.TreeBin<K, V> t = (MyConcurrentHashMap.TreeBin<K, V>) f;
                            MyConcurrentHashMap.TreeNode<K, V> lo = null, loTail = null;
                            MyConcurrentHashMap.TreeNode<K, V> hi = null, hiTail = null;
                            int lc = 0, hc = 0;
                            for (MyConcurrentHashMap.Node<K, V> e = t.first; e != null; e = e.next) {
                                int h = e.hash;
                                MyConcurrentHashMap.TreeNode<K, V> p = new MyConcurrentHashMap.TreeNode<K, V>
                                        (h, e.key, e.val, null, null);
                                if ((h & n) == 0) {
                                    if ((p.prev = loTail) == null)
                                        lo = p;
                                    else
                                        loTail.next = p;
                                    loTail = p;
                                    ++lc;
                                } else {
                                    if ((p.prev = hiTail) == null)
                                        hi = p;
                                    else
                                        hiTail.next = p;
                                    hiTail = p;
                                    ++hc;
                                }
                            }
                            ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                                    (hc != 0) ? new MyConcurrentHashMap.TreeBin<K, V>(lo) : t;
                            hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                                    (lc != 0) ? new MyConcurrentHashMap.TreeBin<K, V>(hi) : t;
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns a list on non-TreeNodes replacing those in given list.
     */
    static <K, V> MyConcurrentHashMap.Node<K, V> untreeify(MyConcurrentHashMap.Node<K, V> b) {
        MyConcurrentHashMap.Node<K, V> hd = null, tl = null;
        for (MyConcurrentHashMap.Node<K, V> q = b; q != null; q = q.next) {
            MyConcurrentHashMap.Node<K, V> p = new MyConcurrentHashMap.Node<K, V>(q.hash, q.key, q.val, null);
            if (tl == null)
                hd = p;
            else
                tl.next = p;
            tl = p;
        }
        return hd;
    }




    /* ---------------- Special Nodes -------------- */

    /**
     * A node inserted at head of bins during transfer operations.
     */
    static final class ForwardingNode<K, V> extends MyConcurrentHashMap.Node<K, V> {
        final MyConcurrentHashMap.Node<K, V>[] nextTable;

        ForwardingNode(MyConcurrentHashMap.Node<K, V>[] tab) {
            super(MOVED, null, null, null);
            this.nextTable = tab;
        }

        MyConcurrentHashMap.Node<K, V> find(int h, Object k) {
            // loop to avoid arbitrarily deep recursion on forwarding nodes
            outer:
            for (MyConcurrentHashMap.Node<K, V>[] tab = nextTable; ; ) {
                MyConcurrentHashMap.Node<K, V> e;
                int n;
                if (k == null || tab == null || (n = tab.length) == 0 ||
                        (e = tabAt(tab, (n - 1) & h)) == null)
                    return null;
                for (; ; ) {
                    int eh;
                    K ek;
                    if ((eh = e.hash) == h &&
                            ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                    if (eh < 0) {
                        if (e instanceof MyConcurrentHashMap.ForwardingNode) {
                            tab = ((MyConcurrentHashMap.ForwardingNode<K, V>) e).nextTable;
                            continue outer;
                        } else
                            return e.find(h, k);
                    }
                    if ((e = e.next) == null)
                        return null;
                }
            }
        }
    }


    /**
     * Helps transfer if a resize is in progress.
     */
    final MyConcurrentHashMap.Node<K, V>[] helpTransfer(MyConcurrentHashMap.Node<K, V>[] tab, MyConcurrentHashMap.Node<K, V> f) {
        MyConcurrentHashMap.Node<K, V>[] nextTab;
        int sc;
        if (tab != null && (f instanceof MyConcurrentHashMap.ForwardingNode) &&
                (nextTab = ((MyConcurrentHashMap.ForwardingNode<K, V>) f).nextTable) != null) {
            int rs = resizeStamp(tab.length);
            while (nextTab == nextTable && table == tab &&
                    (sc = sizeCtl) < 0) {
                if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                        sc == rs + MAX_RESIZERS || transferIndex <= 0)
                    break;
                if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) {
                    transfer(tab, nextTab);
                    break;
                }
            }
            return nextTab;
        }
        return table;
    }

    private static final long serialVersionUID = 1103130828557812207L;

    @Override
    public Set<Entry<K, V>> entrySet() {
        return null;
    }

    // Overrides of JDK8+ Map extension method defaults

    /**
     * Returns the value to which the specified key is mapped, or the
     * given default value if this map contains no mapping for the
     * key.
     *
     * @param key          the key whose associated value is to be returned
     * @param defaultValue the value to return if this map contains
     *                     no mapping for the given key
     * @return the mapping for the key, if present; else the default value
     * @throws NullPointerException if the specified key is null
     */
    public V getOrDefault(Object key, V defaultValue) {
        V v;
        return (v = get(key)) == null ? defaultValue : v;
    }


    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        MyConcurrentHashMap.Node<K, V>[] t;
        if ((t = table) != null) {
            MyConcurrentHashMap.Traverser<K, V> it = new MyConcurrentHashMap.Traverser<K, V>(t, t.length, 0, t.length);
            for (MyConcurrentHashMap.Node<K, V> p; (p = it.advance()) != null; ) {
                action.accept(p.key, p.val);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the specified key is null
     */
    public boolean remove(Object key, Object value) {
        if (key == null)
            throw new NullPointerException();
        return value != null && replaceNode(key, null, value) != null;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if any of the arguments are null
     */
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        return replaceNode(key, newValue, oldValue) != null;
    }

    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     * or {@code null} if there was no mapping for the key
     * @throws NullPointerException if the specified key or value is null
     */
    public V replace(K key, V value) {
        if (key == null || value == null)
            throw new NullPointerException();
        return replaceNode(key, value, null);
    }


    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        MyConcurrentHashMap.Node<K, V>[] t;
        if ((t = table) != null) {
            MyConcurrentHashMap.Traverser<K, V> it = new MyConcurrentHashMap.Traverser<K, V>(t, t.length, 0, t.length);
            for (MyConcurrentHashMap.Node<K, V> p; (p = it.advance()) != null; ) {
                V oldValue = p.val;
                for (K key = p.key; ; ) {
                    V newValue = function.apply(key, oldValue);
                    if (newValue == null)
                        throw new NullPointerException();
                    if (replaceNode(key, newValue, oldValue) != null ||
                            (oldValue = get(key)) == null)
                        break;
                }
            }
        }
    }


    /**
     * Implementation for the four public remove/replace methods:
     * Replaces node value with v, conditional upon match of cv if
     * non-null.  If resulting value is null, delete.
     */
    final V replaceNode(Object key, V value, Object cv) {
        int hash = spread(key.hashCode());
        for (MyConcurrentHashMap.Node<K, V>[] tab = table; ; ) {
            MyConcurrentHashMap.Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0 ||
                    (f = tabAt(tab, i = (n - 1) & hash)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                V oldVal = null;
                boolean validated = false;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            validated = true;
                            for (MyConcurrentHashMap.Node<K, V> e = f, pred = null; ; ) {
                                K ek;
                                if (e.hash == hash &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    V ev = e.val;
                                    if (cv == null || cv == ev ||
                                            (ev != null && cv.equals(ev))) {
                                        oldVal = ev;
                                        if (value != null)
                                            e.val = value;
                                        else if (pred != null)
                                            pred.next = e.next;
                                        else
                                            setTabAt(tab, i, e.next);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        } else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            validated = true;
                            MyConcurrentHashMap.TreeBin<K, V> t = (MyConcurrentHashMap.TreeBin<K, V>) f;
                            MyConcurrentHashMap.TreeNode<K, V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(hash, key, null)) != null) {
                                V pv = p.val;
                                if (cv == null || cv == pv ||
                                        (pv != null && cv.equals(pv))) {
                                    oldVal = pv;
                                    if (value != null)
                                        p.val = value;
                                    else if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                if (validated) {
                    if (oldVal != null) {
                        if (value == null)
                            addCount(-1L, -1);
                        return oldVal;
                    }
                    break;
                }
            }
        }
        return null;
    }


    /**
     * Records the table, its length, and current traversal index for a
     * traverser that must process a region of a forwarded table before
     * proceeding with current table.
     */
    static final class TableStack<K, V> {
        int length;
        int index;
        MyConcurrentHashMap.Node<K, V>[] tab;
        MyConcurrentHashMap.TableStack<K, V> next;
    }


    /**
     * Encapsulates traversal for methods such as containsValue; also
     * serves as a base class for other iterators and spliterators.
     * <p>
     * Method advance visits once each still-valid node that was
     * reachable upon iterator construction. It might miss some that
     * were added to a bin after the bin was visited, which is OK wrt
     * consistency guarantees. Maintaining this property in the face
     * of possible ongoing resizes requires a fair amount of
     * bookkeeping state that is difficult to optimize away amidst
     * volatile accesses.  Even so, traversal maintains reasonable
     * throughput.
     * <p>
     * Normally, iteration proceeds bin-by-bin traversing lists.
     * However, if the table has been resized, then all future steps
     * must traverse both the bin at the current index as well as at
     * (index + baseSize); and so on for further resizings. To
     * paranoically cope with potential sharing by users of iterators
     * across threads, iteration terminates if a bounds checks fails
     * for a table read.
     */
    static class Traverser<K, V> {
        MyConcurrentHashMap.Node<K, V>[] tab;        // current table; updated if resized
        MyConcurrentHashMap.Node<K, V> next;         // the next entry to use
        MyConcurrentHashMap.TableStack<K, V> stack, spare; // to save/restore on ForwardingNodes
        int index;              // index of bin to use next
        int baseIndex;          // current index of initial table
        int baseLimit;          // index bound for initial table
        final int baseSize;     // initial table size

        Traverser(MyConcurrentHashMap.Node<K, V>[] tab, int size, int index, int limit) {
            this.tab = tab;
            this.baseSize = size;
            this.baseIndex = this.index = index;
            this.baseLimit = limit;
            this.next = null;
        }

        /**
         * Advances if possible, returning next valid node, or null if none.
         */
        final MyConcurrentHashMap.Node<K, V> advance() {
            MyConcurrentHashMap.Node<K, V> e;
            if ((e = next) != null)
                e = e.next;
            for (; ; ) {
                MyConcurrentHashMap.Node<K, V>[] t;
                int i, n;  // must use locals in checks
                if (e != null)
                    return next = e;
                if (baseIndex >= baseLimit || (t = tab) == null ||
                        (n = t.length) <= (i = index) || i < 0)
                    return next = null;
                if ((e = tabAt(t, i)) != null && e.hash < 0) {
                    if (e instanceof MyConcurrentHashMap.ForwardingNode) {
                        tab = ((MyConcurrentHashMap.ForwardingNode<K, V>) e).nextTable;
                        e = null;
                        pushState(t, i, n);
                        continue;
                    } else if (e instanceof MyConcurrentHashMap.TreeBin)
                        e = ((MyConcurrentHashMap.TreeBin<K, V>) e).first;
                    else
                        e = null;
                }
                if (stack != null)
                    recoverState(n);
                else if ((index = i + baseSize) >= n)
                    index = ++baseIndex; // visit upper slots if present
            }
        }

        /**
         * Saves traversal state upon encountering a forwarding node.
         */
        private void pushState(MyConcurrentHashMap.Node<K, V>[] t, int i, int n) {
            MyConcurrentHashMap.TableStack<K, V> s = spare;  // reuse if possible
            if (s != null)
                spare = s.next;
            else
                s = new MyConcurrentHashMap.TableStack<K, V>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        /**
         * Possibly pops traversal state.
         *
         * @param n length of current table
         */
        private void recoverState(int n) {
            MyConcurrentHashMap.TableStack<K, V> s;
            int len;
            while ((s = stack) != null && (index += (len = s.length)) >= n) {
                n = len;
                index = s.index;
                tab = s.tab;
                s.tab = null;
                MyConcurrentHashMap.TableStack<K, V> next = s.next;
                s.next = spare; // save for reuse
                stack = next;
                spare = s;
            }
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }


    /**
     * A place-holder node used in computeIfAbsent and compute
     */
    static final class ReservationNode<K,V> extends MyConcurrentHashMap.Node<K,V> {
        ReservationNode() {
            super(RESERVED, null, null, null);
        }

        MyConcurrentHashMap.Node<K,V> find(int h, Object k) {
            return null;
        }
    }


    /**
     * If the specified key is not already associated with a value,
     * attempts to compute its value using the given mapping function
     * and enters it into this map unless {@code null}.  The entire
     * method invocation is performed atomically, so the function is
     * applied at most once per key.  Some attempted update operations
     * on this map by other threads may be blocked while computation
     * is in progress, so the computation should be short and simple,
     * and must not attempt to update any other mappings of this map.
     *
     * @param key key with which the specified value is to be associated
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with
     *         the specified key, or null if the computed value is null
     * @throws NullPointerException if the specified key or mappingFunction
     *         is null
     * @throws IllegalStateException if the computation detectably
     *         attempts a recursive update to this map that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the mappingFunction does so,
     *         in which case the mapping is left unestablished
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int binCount = 0;
        for (MyConcurrentHashMap.Node<K,V>[] tab = table;;) {
            MyConcurrentHashMap.Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                MyConcurrentHashMap.Node<K,V> r = new MyConcurrentHashMap.ReservationNode<K,V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        MyConcurrentHashMap.Node<K,V> node = null;
                        try {
                            if ((val = mappingFunction.apply(key)) != null)
                                node = new MyConcurrentHashMap.Node<K,V>(h, key, val, null);
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                boolean added = false;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (MyConcurrentHashMap.Node<K,V> e = f;; ++binCount) {
                                K ek; V ev;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = e.val;
                                    break;
                                }
                                MyConcurrentHashMap.Node<K,V> pred = e;
                                if ((e = e.next) == null) {
                                    if ((val = mappingFunction.apply(key)) != null) {
                                        added = true;
                                        pred.next = new MyConcurrentHashMap.Node<K,V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            binCount = 2;
                            MyConcurrentHashMap.TreeBin<K,V> t = (MyConcurrentHashMap.TreeBin<K,V>)f;
                            MyConcurrentHashMap.TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(h, key, null)) != null)
                                val = p.val;
                            else if ((val = mappingFunction.apply(key)) != null) {
                                added = true;
                                t.putTreeVal(h, key, val);
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (!added)
                        return val;
                    break;
                }
            }
        }
        if (val != null)
            addCount(1L, binCount);
        return val;
    }

    /**
     * If the value for the specified key is present, attempts to
     * compute a new mapping given the key and its current mapped
     * value.  The entire method invocation is performed atomically.
     * Some attempted update operations on this map by other threads
     * may be blocked while computation is in progress, so the
     * computation should be short and simple, and must not attempt to
     * update any other mappings of this map.
     *
     * @param key key with which a value may be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or remappingFunction
     *         is null
     * @throws IllegalStateException if the computation detectably
     *         attempts a recursive update to this map that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the remappingFunction does so,
     *         in which case the mapping is unchanged
     */
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (MyConcurrentHashMap.Node<K,V>[] tab = table;;) {
            MyConcurrentHashMap.Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (MyConcurrentHashMap.Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        MyConcurrentHashMap.Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        }
                        else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            binCount = 2;
                            MyConcurrentHashMap.TreeBin<K,V> t = (MyConcurrentHashMap.TreeBin<K,V>)f;
                            MyConcurrentHashMap.TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(h, key, null)) != null) {
                                val = remappingFunction.apply(key, p.val);
                                if (val != null)
                                    p.val = val;
                                else {
                                    delta = -1;
                                    if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }



    /**
     * Attempts to compute a mapping for the specified key and its
     * current mapped value (or {@code null} if there is no current
     * mapping). The entire method invocation is performed atomically.
     * Some attempted update operations on this map by other threads
     * may be blocked while computation is in progress, so the
     * computation should be short and simple, and must not attempt to
     * update any other mappings of this Map.
     *
     * @param key key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or remappingFunction
     *         is null
     * @throws IllegalStateException if the computation detectably
     *         attempts a recursive update to this map that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the remappingFunction does so,
     *         in which case the mapping is unchanged
     */
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (MyConcurrentHashMap.Node<K,V>[] tab = table;;) {
            MyConcurrentHashMap.Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                MyConcurrentHashMap.Node<K,V> r = new MyConcurrentHashMap.ReservationNode<K,V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        MyConcurrentHashMap.Node<K,V> node = null;
                        try {
                            if ((val = remappingFunction.apply(key, null)) != null) {
                                delta = 1;
                                node = new MyConcurrentHashMap.Node<K,V>(h, key, val, null);
                            }
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (MyConcurrentHashMap.Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        MyConcurrentHashMap.Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    val = remappingFunction.apply(key, null);
                                    if (val != null) {
                                        delta = 1;
                                        pred.next =
                                                new MyConcurrentHashMap.Node<K,V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            binCount = 1;
                            MyConcurrentHashMap.TreeBin<K,V> t = (MyConcurrentHashMap.TreeBin<K,V>)f;
                            MyConcurrentHashMap.TreeNode<K,V> r, p;
                            if ((r = t.root) != null)
                                p = r.findTreeNode(h, key, null);
                            else
                                p = null;
                            V pv = (p == null) ? null : p.val;
                            val = remappingFunction.apply(key, pv);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }



    /**
     * If the specified key is not already associated with a
     * (non-null) value, associates it with the given value.
     * Otherwise, replaces the value with the results of the given
     * remapping function, or removes if {@code null}. The entire
     * method invocation is performed atomically.  Some attempted
     * update operations on this map by other threads may be blocked
     * while computation is in progress, so the computation should be
     * short and simple, and must not attempt to update any other
     * mappings of this Map.
     *
     * @param key key with which the specified value is to be associated
     * @param value the value to use if absent
     * @param remappingFunction the function to recompute a value if present
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or the
     *         remappingFunction is null
     * @throws RuntimeException or Error if the remappingFunction does so,
     *         in which case the mapping is unchanged
     */
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (MyConcurrentHashMap.Node<K,V>[] tab = table;;) {
            MyConcurrentHashMap.Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                if (casTabAt(tab, i, null, new MyConcurrentHashMap.Node<K,V>(h, key, value, null))) {
                    delta = 1;
                    val = value;
                    break;
                }
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (MyConcurrentHashMap.Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(e.val, value);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        MyConcurrentHashMap.Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    delta = 1;
                                    val = value;
                                    pred.next =
                                            new MyConcurrentHashMap.Node<K,V>(h, key, val, null);
                                    break;
                                }
                            }
                        }
                        else if (f instanceof MyConcurrentHashMap.TreeBin) {
                            binCount = 2;
                            MyConcurrentHashMap.TreeBin<K,V> t = (MyConcurrentHashMap.TreeBin<K,V>)f;
                            MyConcurrentHashMap.TreeNode<K,V> r = t.root;
                            MyConcurrentHashMap.TreeNode<K,V> p = (r == null) ? null :
                                    r.findTreeNode(h, key, null);
                            val = (p == null) ? value :
                                    remappingFunction.apply(p.val, value);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }


    /* ---------------- Counter support -------------- */

    /**
     * A padded cell for distributing counts.  Adapted from LongAdder
     * and Striped64.  See their internal docs for explanation.
     */
    @sun.misc.Contended
    static final class CounterCell {
        volatile long value;

        CounterCell(long x) {
            value = x;
        }
    }


    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long SIZECTL;
    private static final long TRANSFERINDEX;
    private static final long BASECOUNT;
    private static final long CELLSBUSY;
    private static final long CELLVALUE;
    private static final long ABASE;
    private static final int ASHIFT;

    static {
        try {

            U = getUnsafe();
            Class<?> k = ConcurrentHashMap.class;
            SIZECTL = U.objectFieldOffset
                    (k.getDeclaredField("sizeCtl"));
            TRANSFERINDEX = U.objectFieldOffset
                    (k.getDeclaredField("transferIndex"));
            BASECOUNT = U.objectFieldOffset
                    (k.getDeclaredField("baseCount"));
            CELLSBUSY = U.objectFieldOffset
                    (k.getDeclaredField("cellsBusy"));
            Class<?> ck = MyConcurrentHashMap.CounterCell.class;
            CELLVALUE = U.objectFieldOffset
                    (ck.getDeclaredField("value"));
            Class<?> ak = MyConcurrentHashMap.Node[].class;
            ABASE = U.arrayBaseOffset(ak);
            int scale = U.arrayIndexScale(ak);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    /* ---------------- Table Initialization and Resizing -------------- */

    /**
     * Returns the stamp(邮票) bits for resizing a table of size n.
     * Must be negative(消极的) when shifted left by RESIZE_STAMP_SHIFT.
     */
    static final int resizeStamp(int n) {
        return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
    }


    public static void main(String[] args) {
        System.out.println(resizeStamp(20));
        System.out.println(Integer.numberOfLeadingZeros(20) );
        System.out.println(1 << (RESIZE_STAMP_BITS - 1));
        System.out.println(27 |32768 );
    }


}
