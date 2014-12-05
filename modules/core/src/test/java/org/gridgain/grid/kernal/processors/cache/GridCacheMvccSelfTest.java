/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache;

import org.apache.ignite.configuration.*;
import org.apache.ignite.marshaller.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.spi.discovery.tcp.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.gridgain.grid.cache.GridCacheMode.*;

/**
 * Test cases for multi-threaded tests.
 */
public class GridCacheMvccSelfTest extends GridCommonAbstractTest {
    /** Grid. */
    private GridKernal grid;

    /** VM ip finder for TCP discovery. */
    private static TcpDiscoveryIpFinder ipFinder = new GridTcpDiscoveryVmIpFinder(true);

    /**
     *
     */
    public GridCacheMvccSelfTest() {
        super(true /*start grid. */);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        grid = (GridKernal)grid();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        grid = null;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration() throws Exception {
        IgniteConfiguration cfg = super.getConfiguration();

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(disco);

        GridCacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(REPLICATED);

        cfg.setCacheConfiguration(cacheCfg);

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testMarshalUnmarshalCandidate() throws Exception {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> parent = new GridCacheTestEntryEx<>(cache.context(), "1");

        GridCacheMvccCandidate<String> cand = new GridCacheMvccCandidate<>(
            parent,
            UUID.randomUUID(),
            UUID.randomUUID(),
            version(1),
            123,
            version(2),
            123,
            /*local*/false,
            /*reentry*/false,
            true,
            false,
            false,
            false
        );

        IgniteMarshaller marshaller = getTestResources().getMarshaller();

        GridCacheMvccCandidate<String> unmarshalled = marshaller.unmarshal(marshaller.marshal(cand), null);

        info(unmarshalled.toString());
    }

    /**
     * Tests remote candidates.
     */
    public void testRemotes() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);

        entry.addRemote(node1, 1, ver1, 0, false, true);

        Collection<GridCacheMvccCandidate<String>> cands = entry.remoteMvccSnapshot();

        assert cands.size() == 1;
        assert cands.iterator().next().version().equals(ver1);

        entry.addRemote(node2, 5, ver5, 0, false, true);

        cands = entry.remoteMvccSnapshot();

        assert cands.size() == 2;

        info(cands);

        // Check order.
        checkOrder(cands, ver1, ver5);

        entry.addRemote(node1, 3, ver3, 0, false, true);

        cands = entry.remoteMvccSnapshot();

        info(cands);

        assert cands.size() == 3;

        // No reordering happens.
        checkOrder(cands, ver1, ver5, ver3);

        entry.doneRemote(ver3);

        checkDone(entry.candidate(ver3));

        entry.addRemote(node1, 2, ver2, 0, false, true);

        cands = entry.remoteMvccSnapshot();

        info(cands);

        assert cands.size() == 4;

        // Check order.
        checkOrder(cands, ver1, ver5, ver3, ver2);

        entry.orderCompleted(
            new GridCacheVersion(1, 0, 2, 0, 0),
            Arrays.asList(new GridCacheVersion(1, 0, 3, 4, 0), ver2, new GridCacheVersion(1, 0, 5, 6, 0)),
            Collections.<GridCacheVersion>emptyList()
        );

        cands = entry.remoteMvccSnapshot();

        info(cands);

        assert cands.size() == 4;

        // Done ver 2.
        checkOrder(cands, ver1, ver2, ver5, ver3);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, true, false);
        checkRemote(entry.candidate(ver3), ver3, true, true);
        checkRemote(entry.candidate(ver5), ver5, false, false);

        entry.doneRemote(ver5);

        checkDone(entry.candidate(ver5));

        entry.addRemote(node1, 4, ver4, 0, false, true);

        cands = entry.remoteMvccSnapshot();

        info(cands);

        assert cands.size() == 5;

        // Check order.
        checkOrder(cands, ver1, ver2, ver5, ver3, ver4);

        entry.orderCompleted(ver3, Arrays.asList(ver2, ver5), Collections.<GridCacheVersion>emptyList());

        cands = entry.remoteMvccSnapshot();

        info(cands);

        assert cands.size() == 5;

        checkOrder(cands, ver1, ver2, ver5, ver3, ver4);

        assert entry.anyOwner() == null;

        entry.doneRemote(ver1);

        checkRemoteOwner(entry.anyOwner(), ver1);

        entry.removeLock(ver1);

        assert entry.remoteMvccSnapshot().size() == 4;

        assert entry.anyOwner() == null;

        entry.doneRemote(ver2);

        checkRemoteOwner(entry.anyOwner(), ver2);

        entry.removeLock(ver2);

        assert entry.remoteMvccSnapshot().size() == 3;

        checkRemoteOwner(entry.anyOwner(), ver5);

        entry.removeLock(ver3);

        assert entry.remoteMvccSnapshot().size() == 2;

        checkRemoteOwner(entry.anyOwner(), ver5);

        entry.removeLock(ver5);

        assert entry.remoteMvccSnapshot().size() == 1;

        assert entry.anyOwner() == null;

        entry.doneRemote(ver4);

        checkRemoteOwner(entry.anyOwner(), ver4);

        entry.removeLock(ver4);

        assert entry.remoteMvccSnapshot().isEmpty();

        assert entry.anyOwner() == null;
    }

    /**
     * Tests that orderOwned does not reorder owned locks.
     */
    public void testNearRemoteWithOwned() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);

        GridCacheMvccCandidate<String> c1 = entry.addRemote(node1, 1, ver1, 0, false, true);
        GridCacheMvccCandidate<String> c2 = entry.addRemote(node1, 1, ver2, 0, false, true);
        GridCacheMvccCandidate<String> c3 = entry.addRemote(node1, 1, ver3, 0, false, true);
        GridCacheMvccCandidate<String> c4 = entry.addRemote(node1, 1, ver4, 0, false, true);

        GridCacheMvccCandidate[] candArr = new GridCacheMvccCandidate[] {c1, c2, c3, c4};

        Collection<GridCacheMvccCandidate<String>> rmtCands = entry.remoteMvccSnapshot();

        assert rmtCands.size() == 4;
        assert rmtCands.iterator().next().version().equals(ver1);

        entry.orderOwned(ver1, ver2);

        rmtCands = entry.remoteMvccSnapshot();

        int i = 0;

        for (GridCacheMvccCandidate<String> cand : rmtCands) {
            assertTrue(cand == candArr[i]);

            assertTrue(ver2.equals(cand.ownerVersion()) || cand != c1);

            i++;
        }
    }

    /**
     * Tests that orderOwned does not reorder owned locks.
     */
    public void testNearRemoteWithOwned1() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);

        GridCacheMvccCandidate<String> c1 = entry.addRemote(node1, 1, ver1, 0, false, true);
        GridCacheMvccCandidate<String> c2 = entry.addRemote(node1, 1, ver2, 0, false, true);
        GridCacheMvccCandidate<String> c3 = entry.addRemote(node1, 1, ver3, 0, false, true);
        GridCacheMvccCandidate<String> c4 = entry.addRemote(node1, 1, ver4, 0, false, true);
        GridCacheMvccCandidate<String> c5 = entry.addRemote(node1, 1, ver5, 0, false, true);
        GridCacheMvccCandidate<String> c6 = entry.addRemote(node1, 1, ver6, 0, false, true);

        GridCacheMvccCandidate[] candArr = new GridCacheMvccCandidate[] {c1, c2, c3, c4, c5, c6};

        Collection<GridCacheMvccCandidate<String>> cands = entry.remoteMvccSnapshot();

        assert cands.size() == 6;
        assert cands.iterator().next().version().equals(ver1);

        entry.orderOwned(ver1, ver3);

        cands = entry.remoteMvccSnapshot();

        int i = 0;

        for (GridCacheMvccCandidate<String> cand : cands) {
            assert cand == candArr[i];

            assertTrue(ver3.equals(cand.ownerVersion()) || cand != c1);

            i++;
        }
    }

    /**
     * Tests that orderOwned does not reorder owned locks.
     */
    public void testNearRemoteWithOwned2() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();

        GridCacheVersion ver0 = version(0);
        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);

        GridCacheMvccCandidate<String> c0 = entry.addRemote(node1, 1, ver0, 0, false, true);
        GridCacheMvccCandidate<String> c1 = entry.addRemote(node1, 1, ver1, 0, false, true);
        GridCacheMvccCandidate<String> c2 = entry.addRemote(node1, 1, ver2, 0, false, true);
        GridCacheMvccCandidate<String> c3 = entry.addRemote(node1, 1, ver3, 0, false, true);
        GridCacheMvccCandidate<String> c4 = entry.addRemote(node1, 1, ver4, 0, false, true);
        GridCacheMvccCandidate<String> c5 = entry.addRemote(node1, 1, ver5, 0, false, true);
        GridCacheMvccCandidate<String> c6 = entry.addRemote(node1, 1, ver6, 0, false, true);

        GridCacheMvccCandidate[] candArr = new GridCacheMvccCandidate[] {c0, c1, c2, c3, c4, c5, c6};

        Collection<GridCacheMvccCandidate<String>> cands = entry.remoteMvccSnapshot();

        assert cands.size() == 7;
        assert cands.iterator().next().version().equals(ver0);

        entry.orderOwned(ver1, ver2);

        cands = entry.remoteMvccSnapshot();

        int i = 0;

        for (GridCacheMvccCandidate<String> cand : cands) {
            assert cand == candArr[i];

            assertTrue(ver2.equals(cand.ownerVersion()) || cand != c1);

            i++;
        }
    }

    /**
     * Tests remote candidates.
     */
    public void testLocal() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);

        entry.addLocal(3, ver3, 0, true, true);

        Collection<GridCacheMvccCandidate<String>> cands = entry.localCandidates();

        assert cands.size() == 1;

        assert cands.iterator().next().version().equals(ver3);

        entry.addLocal(5, ver5, 0, true, true);

        cands = entry.localCandidates();

        assert cands.size() == 2;

        info(cands);

        // Check order.
        checkOrder(cands, ver3, ver5);

        assert entry.anyOwner() == null;

        entry.readyLocal(ver3);

        checkLocalOwner(entry.anyOwner(), ver3, false);

        // Reentry.
        entry.addLocal(3, ver4, 0, true, true);

        checkLocalOwner(entry.anyOwner(), ver4, true);

        assert entry.localCandidates().size() == 2;
        assert entry.localCandidates(true).size() == 3;

        // Check order.
        checkOrder(entry.localCandidates(true), ver4, ver3, ver5);

        entry.removeLock(ver4);

        assert entry.localCandidates(true).size() == 2;

        entry.readyLocal(ver5);

        checkLocalOwner(entry.anyOwner(), ver3, false);

        // Check order.
        checkOrder(entry.localCandidates(true), ver3, ver5);

        entry.removeLock(ver3);

        assert entry.localCandidates(true).size() == 1;

        checkLocalOwner(entry.anyOwner(), ver5, false);

        assert !entry.lockedByAny(ver5);

        entry.removeLock(ver5);

        assert !entry.lockedByAny();
        assert entry.anyOwner() == null;
        assert entry.localCandidates(true).isEmpty();
    }

    /**
     * Tests assignment of local candidates when remote exist.
     */
    public void testLocalWithRemote() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID nodeId = UUID.randomUUID();

        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);

        entry.addRemote(nodeId, 1, ver2, 0, false, true);

        entry.addLocal(3, ver3, 0, false, true);

        assert entry.anyOwner() == null;

        entry.readyLocal(ver3);

        assert entry.anyOwner() == null;

        entry.removeLock(ver2);

        assert entry.localCandidates().size() == 1;

        checkLocalOwner(entry.anyOwner(), ver3, false);

        entry.removeLock(ver3);

        assert !entry.lockedByAny();
        assert entry.anyOwner() == null;
        assert entry.localCandidates(true).isEmpty();
    }

    /**
     *
     */
    public void testCompletedWithBaseInTheMiddle() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);
        GridCacheVersion ver8 = version(8);

        entry.addRemote(node1, 1, ver1, 0, false, true);
        entry.addRemote(node2, 2, ver2, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);
        entry.addRemote(node1, 5, ver5, 0, false, true);
        entry.addRemote(node2, 7, ver7, 0, false, true);
        entry.addRemote(node2, 8, ver8, 0, false, true);

        GridCacheMvccCandidate<String> doomed = entry.addRemote(node2, 6, ver6, 0, false, true);

        // No reordering happens.
        checkOrder(entry.remoteMvccSnapshot(), ver1, ver2, ver3, ver4, ver5, ver7, ver8, ver6);

        List<GridCacheVersion> committed = Arrays.asList(ver4, ver7);
        List<GridCacheVersion> rolledback = Arrays.asList(ver6);

        entry.orderCompleted(ver2, committed, rolledback);

        assert !entry.lockedBy(ver6);

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver4, ver7, ver2, ver3, ver5, ver8);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver7), ver7, true, false);
        checkRemote(entry.candidate(ver8), ver8, false, false);

        checkRemote(doomed, ver6, false, true);
    }

    /**
     *
     */
    public void testCompletedWithCompletedBaseInTheMiddle() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        entry.addRemote(node1, 1, ver1, 0, false, true);
        entry.addRemote(node2, 2, ver2, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);
        entry.addRemote(node1, 5, ver5, 0, false, true);
        entry.addRemote(node2, 6, ver6, 0, false, true);
        entry.addRemote(node2, 7, ver7, 0, false, true);

        List<GridCacheVersion> committed = Arrays.asList(ver4, ver6, ver2);

        entry.orderCompleted(ver2, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver4, ver6, ver2, ver3, ver5, ver7);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, true, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver6), ver6, true, false);
        checkRemote(entry.candidate(ver7), ver7, false, false);
    }

    /**
     *
     */
    public void testCompletedTwiceWithBaseInTheMiddle() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        entry.addRemote(node1, 1, ver1, 0, false, true);
        entry.addRemote(node2, 2, ver2, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);
        entry.addRemote(node1, 5, ver5, 0, false, true);
        entry.addRemote(node2, 6, ver6, 0, false, true);
        entry.addRemote(node2, 7, ver7, 0, false, true);

        List<GridCacheVersion> completed = Arrays.asList(ver4, ver6);

        entry.orderCompleted(ver2, completed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver4, ver6, ver2, ver3, ver5, ver7);

        entry.orderCompleted(ver4, completed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver6, ver4, ver2, ver3, ver5, ver7);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver6), ver6, true, false);
        checkRemote(entry.candidate(ver7), ver7, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseInTheMiddleNoChange() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        entry.addRemote(node1, 1, ver1, 0, false, false);
        entry.addRemote(node2, 2, ver2, 0, false, false);
        entry.addRemote(node1, 3, ver3, 0, false, false);
        entry.addRemote(node2, 4, ver4, 0, false, false);
        entry.addRemote(node1, 5, ver5, 0, false, false);

        List<GridCacheVersion> committed = Arrays.asList(ver6, ver7);

        entry.orderCompleted(ver4, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver2, ver3, ver4, ver5);

        // Nothing set to owner since there is no change.
        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, false, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseInTheBeginning() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        entry.addRemote(node1, 1, ver1, 0, false, false);
        entry.addRemote(node2, 2, ver2, 0, false, false);
        entry.addRemote(node1, 3, ver3, 0, false, false);
        entry.addRemote(node2, 4, ver4, 0, false, false);
        entry.addRemote(node1, 5, ver5, 0, false, false);
        entry.addRemote(node2, 6, ver6, 0, false, false);
        entry.addRemote(node2, 7, ver7, 0, false, false);

        List<GridCacheVersion> committed = Arrays.asList(ver4, ver6, ver3);

        entry.orderCompleted(ver1, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver3, ver4, ver6, ver1, ver2, ver5, ver7);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, true, false);
        checkRemote(entry.candidate(ver4), ver4, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver6), ver6, true, false);
        checkRemote(entry.candidate(ver7), ver7, false, false);
    }

    /**
     * This case should never happen, nevertheless we need to test for it.
     */
    public void testCompletedWithBaseInTheBeginningNoChange() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        entry.addRemote(node1, 1, ver1, 0, false, false);
        entry.addRemote(node2, 2, ver2, 0, false, false);
        entry.addRemote(node1, 3, ver3, 0, false, false);
        entry.addRemote(node2, 4, ver4, 0, false, false);
        entry.addRemote(node1, 5, ver5, 0, false, false);

        List<GridCacheVersion> committed = Arrays.asList(ver6, ver7);

        entry.orderCompleted(ver1, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver2, ver3, ver4, ver5);

        // Nothing set to owner since there is no change.
        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, false, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
    }

    /**
     * This case should never happen, nevertheless we need to test for it.
     */
    public void testCompletedWithBaseInTheEndNoChange() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        entry.addRemote(node1, 1, ver1, 0, false, false);
        entry.addRemote(node2, 2, ver2, 0, false, false);
        entry.addRemote(node1, 3, ver3, 0, false, false);
        entry.addRemote(node2, 4, ver4, 0, false, false);
        entry.addRemote(node1, 5, ver5, 0, false, false);

        List<GridCacheVersion> committed = Arrays.asList(ver6, ver7);

        entry.orderCompleted(ver5, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver2, ver3, ver4, ver5);

        // Nothing set to owner since there is no change.
        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, false, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseNotPresentInTheMiddle() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        // Don't add version 2.
        entry.addRemote(node1, 1, ver1, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);
        entry.addRemote(node1, 5, ver5, 0, false, true);
        entry.addRemote(node2, 6, ver6, 0, false, true);
        entry.addRemote(node2, 7, ver7, 0, false, true);

        List<GridCacheVersion> committed = Arrays.asList(ver6, ver4);

        entry.orderCompleted(ver2, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver4, ver6, ver3, ver5, ver7);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver6), ver6, true, false);
        checkRemote(entry.candidate(ver7), ver7, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseNotPresentInTheMiddleNoChange() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        // Don't add versions 2, 5, 6, 7.
        entry.addRemote(node1, 1, ver1, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);

        List<GridCacheVersion> committed = Arrays.asList(ver6, ver5, ver7);

        entry.orderCompleted(ver2, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver3, ver4);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseNotPresentInTheBeginning() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        // Don't add version 1.
        entry.addRemote(node1, 2, ver2, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);
        entry.addRemote(node1, 5, ver5, 0, false, true);
        entry.addRemote(node2, 6, ver6, 0, false, true);
        entry.addRemote(node2, 7, ver7, 0, false, true);

        List<GridCacheVersion> committed = Arrays.asList(ver4, ver6, ver3);

        entry.orderCompleted(ver1, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver3, ver4, ver6, ver2, ver5, ver7);

        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, true, false);
        checkRemote(entry.candidate(ver4), ver4, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver6), ver6, true, false);
        checkRemote(entry.candidate(ver7), ver7, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseNotPresentInTheBeginningNoChange() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        // Don't add version 6, 7
        entry.addRemote(node1, 2, ver2, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);
        entry.addRemote(node1, 5, ver5, 0, false, true);
        entry.addRemote(node1, 6, ver6, 0, false, true);
        entry.addRemote(node1, 7, ver7, 0, false, true);

        List<GridCacheVersion> committed = Arrays.asList(ver2, ver3);

        entry.orderCompleted(ver1, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver2, ver3, ver4, ver5, ver6, ver7);

        checkRemote(entry.candidate(ver2), ver2, true, false);
        checkRemote(entry.candidate(ver3), ver3, true, false);
        checkRemote(entry.candidate(ver4), ver4, false, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);
        checkRemote(entry.candidate(ver6), ver6, false, false);
        checkRemote(entry.candidate(ver7), ver7, false, false);
    }

    /**
     *
     */
    public void testCompletedWithBaseNotPresentInTheEndNoChange() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);
        GridCacheVersion ver6 = version(6);
        GridCacheVersion ver7 = version(7);

        // Don't add version 5, 6, 7
        entry.addRemote(node1, 1, ver1, 0, false, true);
        entry.addRemote(node1, 2, ver2, 0, false, true);
        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addRemote(node2, 4, ver4, 0, false, true);

        List<GridCacheVersion> committed = Arrays.asList(ver6, ver7);

        entry.orderCompleted(ver5, committed, Collections.<GridCacheVersion>emptyList());

        checkOrder(entry.remoteMvccSnapshot(), ver1, ver2, ver3, ver4);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver2), ver2, false, false);
        checkRemote(entry.candidate(ver3), ver3, false, false);
        checkRemote(entry.candidate(ver4), ver4, false, false);
    }

    /**
     * Test local and remote candidates together.
     */
    public void testLocalAndRemote() {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        UUID node1 = UUID.randomUUID();
        UUID node2 = UUID.randomUUID();

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);
        GridCacheVersion ver5 = version(5);

        entry.addRemote(node1, 1, ver1, 0, false, false);
        entry.addLocal(2, ver2, 0, true, true);

        Collection<GridCacheMvccCandidate<String>> cands = entry.remoteMvccSnapshot();

        assert cands.size() == 1;
        assert cands.iterator().next().version().equals(ver1);

        entry.addRemote(node2, 5, ver5, 0, false, false);

        cands = entry.remoteMvccSnapshot();

        assert cands.size() == 2;

        info(cands);

        checkOrder(cands, ver1, ver5);
        checkOrder(entry.localCandidates(true), ver2);

        entry.addRemote(node1, 3, ver3, 0, false, true);
        entry.addLocal(4, ver4, 0, /*reenter*/true, false);

        cands = entry.remoteMvccSnapshot();

        assert cands.size() == 3;

        // Check order.
        checkOrder(entry.remoteMvccSnapshot(), ver1, ver5, ver3);
        checkOrder(entry.localCandidates(), ver2, ver4);

        entry.orderCompleted(
            ver2 /*local version.*/,
            Arrays.asList(new GridCacheVersion(1, 0, 1, 2, 0), ver3, new GridCacheVersion(1, 0, 5, 6, 0)),
            Collections.<GridCacheVersion>emptyList()
        );

        // Done ver3.
        checkOrder(entry.remoteMvccSnapshot(), ver1, ver3, ver5);
        checkOrder(entry.localCandidates(), ver2, ver4);

        checkRemote(entry.candidate(ver1), ver1, false, false);
        checkRemote(entry.candidate(ver3), ver3, true, false);
        checkRemote(entry.candidate(ver5), ver5, false, false);

        checkLocal(entry.candidate(ver2), ver2, false, false, false);
        checkLocal(entry.candidate(ver4), ver4, false, false, false);

        entry.readyLocal(ver2);

        checkLocal(entry.candidate(ver2), ver2, true, false, false);
        checkLocal(entry.candidate(ver4), ver4, false, false, false);

        assert entry.anyOwner() == null;

        entry.doneRemote(ver1);

        checkRemoteOwner(entry.anyOwner(), ver1);

        entry.removeLock(ver1);

        checkOrder(entry.remoteMvccSnapshot(), ver3, ver5);

        assert entry.anyOwner() == null;

        entry.doneRemote(ver3);

        checkRemoteOwner(entry.anyOwner(), ver3);

        entry.removeLock(ver3);

        checkLocalOwner(entry.anyOwner(), ver2, false);

        entry.removeLock(ver2);

        assert !entry.lockedByAny(ver4, ver5);

        checkOrder(entry.remoteMvccSnapshot(), ver5);
        checkOrder(entry.localCandidates(), ver4);

        assert entry.anyOwner() == null;

        entry.readyLocal(ver4);

        checkLocalOwner(entry.anyOwner(), ver4, false);

        entry.removeLock(ver4);

        assert entry.anyOwner() == null;

        GridCacheMvccCandidate<String> c5 = entry.candidate(ver5);

        assert c5 != null;

        c5.setOwner();

        assert entry.anyOwner() == null;

        entry.doneRemote(ver5);

        checkRemoteOwner(entry.anyOwner(), ver5);

        assert !entry.lockedByAny(ver5);

        entry.removeLock(ver5);

        assert !entry.lockedByAny();
        assert entry.anyOwner() == null;
    }

    /**
     * @throws Exception If test failed.
     */
    public void testMultipleLocalAndRemoteLocks1() throws Exception {
        UUID nodeId = UUID.randomUUID();

        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(ctx, "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(ctx, "2");
        GridCacheTestEntryEx<String, String> entry3 = new GridCacheTestEntryEx<>(ctx, "3");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);

        GridCacheMvccCandidate<String> c13 = entry1.addLocal(1, ver3, 0, true, false);

        entry1.readyLocal(ver3);

        checkLocalOwner(entry1.candidate(ver3), ver3, false);

        GridCacheMvccCandidate<String> c11 = entry1.addLocal(2, ver1, 0, true, true);
        GridCacheMvccCandidate<String> c21 = entry2.addLocal(2, ver1, 0, true, true);
        GridCacheMvccCandidate<String> c31 = entry3.addLocal(2, ver1, 0, true, true);

        GridCacheMvccCandidate<String> c33 = entry3.addLocal(1, ver3, 0, true, false);

        linkCandidates(ctx, c11, c21, c31);

        entry1.readyLocal(ver1);
        entry2.readyLocal(ver1);
        entry3.readyLocal(ver1);

        checkLocal(entry1.candidate(ver1), ver1, true, false, false);
        checkLocal(entry2.candidate(ver1), ver1, true, false, false);
        checkLocal(entry3.candidate(ver1), ver1, true, false, false);

        checkLocal(entry3.candidate(ver3), ver3, false, false, false);

        linkCandidates(ctx, c13, c33);

        entry2.addRemote(nodeId, 3, ver2, 0, false, true);

        checkLocal(entry2.candidate(ver1), ver1, true, false, false);

        entry3.addRemote(nodeId, 3, ver2, 0, false, false);

        entry3.readyLocal(ver3);

        checkLocal(entry1.candidate(ver3), ver3, true, true, false);
        checkLocal(entry3.candidate(ver3), ver3, true, true, false);
        checkLocal(entry1.candidate(ver1), ver1, true, false, false);
        checkLocal(entry2.candidate(ver1), ver1, true, false, false);
        checkLocal(entry3.candidate(ver1), ver1, true, false, false);

        entry1.releaseLocal(1);

        entry2.recheckLock();

        checkLocal(entry1.candidate(ver1), ver1, true, true, false);
        checkLocal(entry2.candidate(ver1), ver1, true, true, false);
        checkLocal(entry3.candidate(ver1), ver1, true, false, false);

        entry3.releaseLocal(1);

        checkLocal(entry1.candidate(ver1), ver1, true, true, false);
        checkLocal(entry2.candidate(ver1), ver1, true, true, false);
        checkLocal(entry3.candidate(ver1), ver1, true, true, false);
    }

    /**
     * @throws Exception If test failed.
     */
    public void testMultipleLocalAndRemoteLocks2() throws Exception {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(ctx, "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(ctx, "2");
        GridCacheTestEntryEx<String, String> entry3 = new GridCacheTestEntryEx<>(ctx, "3");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);

        GridCacheMvccCandidate<String> c13 = entry1.addLocal(1, ver3, 0, true, true);

        entry1.readyLocal(ver3);

        checkLocalOwner(entry1.candidate(ver3), ver3, false);

        GridCacheMvccCandidate<String> c11 = entry1.addLocal(2, ver2, 0, true, false);
        GridCacheMvccCandidate<String> c21 = entry2.addLocal(2, ver2, 0, true, false);
        GridCacheMvccCandidate<String> c31 = entry3.addLocal(2, ver2, 0, true, false);

        GridCacheMvccCandidate<String> c33 = entry3.addLocal(1, ver3, 0, true, true);

        linkCandidates(ctx, c11, c21, c31);

        entry1.readyLocal(ver2);
        entry2.readyLocal(ver2);
        entry3.readyLocal(ver2);

        checkLocal(entry1.candidate(ver2), ver2, true, false, false);
        checkLocal(entry2.candidate(ver2), ver2, true, false, false);
        checkLocal(entry3.candidate(ver2), ver2, true, false, false);

        checkLocal(entry3.candidate(ver3), ver3, false, false, false);

        linkCandidates(ctx, c13, c33);

        entry2.addRemote(UUID.randomUUID(), 3, ver1, 0, false, true);

        checkLocal(entry2.candidate(ver2), ver2, true, false, false);

        entry3.addRemote(UUID.randomUUID(), 3, ver1, 0, false, true);

        entry3.readyLocal(ver3);

        checkLocal(entry1.candidate(ver3), ver3, true, true, false);
        checkLocal(entry3.candidate(ver3), ver3, true, false, false);
        checkLocal(entry1.candidate(ver2), ver2, true, false, false);
        checkLocal(entry2.candidate(ver2), ver2, true, false, false);
        checkLocal(entry3.candidate(ver2), ver2, true, false, false);

        entry2.removeLock(ver1);

        checkLocal(entry1.candidate(ver3), ver3, true, true, false);
        checkLocal(entry3.candidate(ver3), ver3, true, false, false);
        checkLocal(entry1.candidate(ver2), ver2, true, false, false);
        checkLocal(entry2.candidate(ver2), ver2, true, false, false);
        checkLocal(entry3.candidate(ver2), ver2, true, false, false);

        entry3.removeLock(ver1);

        checkLocal(entry1.candidate(ver3), ver3, true, true, false);
        checkLocal(entry3.candidate(ver3), ver3, true, true, false);
        checkLocal(entry1.candidate(ver2), ver2, true, false, false);
        checkLocal(entry2.candidate(ver2), ver2, true, false, false);
        checkLocal(entry3.candidate(ver2), ver2, true, false, false);

        entry1.releaseLocal(1);

        entry2.recheckLock();

        checkLocal(entry1.candidate(ver2), ver2, true, true, false);
        checkLocal(entry2.candidate(ver2), ver2, true, true, false);
        checkLocal(entry3.candidate(ver2), ver2, true, false, false);

        entry3.releaseLocal(1);

        checkLocal(entry1.candidate(ver2), ver2, true, true, false);
        checkLocal(entry2.candidate(ver2), ver2, true, true, false);
        checkLocal(entry3.candidate(ver2), ver2, true, true, false);
    }

    /**
     * @throws Exception If test failed.
     */
    public void testMultipleLocalLocks() throws Exception {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(ctx, "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(ctx, "2");
        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver3 = version(3);

        GridCacheMvccCandidate<String> c13 = entry1.addLocal(1, ver3, 0, true, false);

        entry1.readyLocal(ver3);

        checkLocalOwner(entry1.candidate(ver3), ver3, false);

        GridCacheMvccCandidate<String> c11 = entry1.addLocal(2, ver1, 0, true, true);

        GridCacheMvccCandidate<String> c21 = entry2.addLocal(2, ver1, 0, true, false);

        linkCandidates(ctx, c11, c21);

        entry1.readyLocal(ver1);
        entry2.readyLocal(ver1);

        checkLocal(entry1.candidate(ver1), ver1, true, false, false);
        checkLocal(entry2.candidate(ver1), ver1, true, false, false);

        GridCacheMvccCandidate<String> c23 = entry2.addLocal(1, ver3, 0, true, true);

        linkCandidates(ctx, c13, c23);

        entry2.readyLocal(ver3);

        checkLocalOwner(entry2.candidate(ver3), ver3, false);
    }

    /**
     * @throws Exception If failed.
     */
    @SuppressWarnings({"ObjectEquality"})
    public void testUsedCandidates() throws Exception {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry = new GridCacheTestEntryEx<>(cache.context(), "1");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);
        GridCacheVersion ver4 = version(4);

        GridCacheMvccCandidate<String> c1 = entry.addLocal(1, ver1, 0, true, false);

        ctx.mvcc().addNext(ctx, c1);

        GridCacheMvccCandidate<String> c2 = entry.addLocal(2, ver2, 0, true, false);

        ctx.mvcc().addNext(ctx, c2);

        GridCacheMvccCandidate<String> c3 = entry.addLocal(3, ver3, 0, true, true);

        ctx.mvcc().addNext(ctx, c3);

        checkLocal(entry.candidate(ver1), ver1, false, false, false);
        checkLocal(entry.candidate(ver2), ver2, false, false, false);
        checkLocal(entry.candidate(ver3), ver3, false, false, false);

        entry.readyLocal(ver1);
        entry.readyLocal(ver3);

        checkLocal(entry.candidate(ver1), ver1, true, true, false);
        checkLocal(entry.candidate(ver2), ver2, false, false, false);
        checkLocal(entry.candidate(ver3), ver3, true, false, false);

        entry.removeLock(ver2);

        assert c3 != null;
        assert c3.previous() == c2;
        assert c2 != null;
        assert c2.previous() == c1;

        checkLocal(c2, ver2, false, false, false, true);

        checkLocal(entry.candidate(ver1), ver1, true, true, false);
        checkLocal(entry.candidate(ver3), ver3, true, false, false);

        entry.removeLock(ver1);

        assert c3.previous() == c2;
        assert c2.previous() == c1;

        checkLocal(entry.candidate(ver3), ver3, true, true, false);

        GridCacheMvccCandidate<String> c4 = entry.addLocal(4, ver4, 0, true, true);

        ctx.mvcc().addNext(ctx, c4);

        assert c3.previous() == null;
        assert c4 != null;
        assert c4.previous() == c3;
    }

    /**
     * Test 2 keys with candidates in reverse order.
     */
    public void testReverseOrder1() {
        UUID id = UUID.randomUUID();

        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(cache.context(), "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(cache.context(), "2");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);

        GridCacheMvccCandidate<String> c1k1 = entry1.addLocal(2, ver2, 0, true, false);

        // Link up.
        ctx.mvcc().addNext(ctx, c1k1);

        entry1.readyLocal(ver2);

        checkLocal(c1k1, ver2, true, true, false);

        GridCacheMvccCandidate<String> c2k1 = entry1.addRemote(id, 2, ver1, 0, false, true);

        // Force recheck of assignments.
        entry1.recheckLock();

        checkLocal(c1k1, ver2, true, true, false);
        checkRemote(c2k1, ver1, false, false);

        GridCacheMvccCandidate<String> c1k2 = entry2.addLocal(2, ver2, 0, true, false);

        assert c1k2 != null;

        ctx.mvcc().addNext(ctx, c1k2);

        assert c1k2.previous() == c1k1;

        GridCacheMvccCandidate<String> c2k2 = entry2.addRemote(id, 3, ver1, 0, false, true);

        entry2.readyLocal(c1k2);

        // Local entry2 should become owner, because otherwise remote will be stuck, waiting
        // for local entry1, which in turn awaits for local entry2.
        checkLocal(c1k2, ver2, true, true, false);
        checkRemote(c2k2, ver1, false, false);
    }

    /**
     * Test 2 keys with candidates in reverse order.
     *
     * @throws Exception If failed.
     */
    public void testReverseOrder2() throws Exception {
        UUID id = UUID.randomUUID();

        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(ctx, "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(ctx, "2");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);

        // Local with higher version.
        GridCacheMvccCandidate<String> v3k1 = entry1.addLocal(3, ver3, 0, true, true);
        GridCacheMvccCandidate<String> v3k2 = entry2.addLocal(3, ver3, 0, true, true);

        // Link up.
        linkCandidates(ctx, v3k1, v3k2);

        entry1.readyLocal(v3k1);

        checkLocal(v3k1, ver3, true, true, false);
        checkLocal(v3k2, ver3, false, false, false);

        // Remote locks.
        GridCacheMvccCandidate<String> v2k1 = entry1.addRemote(id, 3, ver2, 0, false, false);
        GridCacheMvccCandidate<String> v2k2 = entry2.addRemote(id, 3, ver2, 0, false, false);

        checkRemote(v2k1, ver2, false, false);
        checkRemote(v2k2, ver2, false, false);

        // Local with lower version.
        GridCacheMvccCandidate<String> v1k1 = entry1.addLocal(4, ver1, 0, true, true);
        GridCacheMvccCandidate<String> v1k2 = entry2.addLocal(4, ver1, 0, true, true);

        // Link up.
        linkCandidates(ctx, v1k1, v1k2);

        entry1.readyLocal(v1k1);
        entry2.readyLocal(v1k2);

        checkLocal(v1k1, ver1, true, false, false);
        checkLocal(v1k2, ver1, true, false, false);

        checkLocal(v3k2, ver3, false, false, false);

        entry2.readyLocal(v3k2);

        // Note, ver3 should be acquired before ver1 (i.e. in reverse order from natural versions order).
        checkLocal(v3k2, ver3, true, true, false);

        checkLocal(v1k1, ver1, true, false, false);
        checkLocal(v1k2, ver1, true, false, false);
    }

    /**
     * Tests local-only locks in reverse order.
     *
     * @throws Exception If failed.
     */
    public void testReverseOrder3() throws Exception {
        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(ctx, "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(ctx, "2");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver3 = version(3);

        // Local with higher version.
        GridCacheMvccCandidate<String> v3k1 = entry1.addLocal(3, ver3, 0, true, false);
        GridCacheMvccCandidate<String> v3k2 = entry2.addLocal(3, ver3, 0, true, false);

        linkCandidates(ctx, v3k1, v3k2);

        entry1.readyLocal(ver3);

        checkLocal(v3k1, ver3, true, true, false);
        checkLocal(v3k2, ver3, false, false, false);

        GridCacheMvccCandidate<String> v1k1 = entry1.addLocal(4, ver1, 0, true, true);
        GridCacheMvccCandidate<String> v1k2 = entry2.addLocal(4, ver1, 0, true, true);

        // Link up.
        linkCandidates(ctx, v1k1, v1k2);

        entry1.readyLocal(ver1);
        entry2.readyLocal(ver1);

        checkLocal(v3k1, ver3, true, true, false); // Owner.
        checkLocal(v3k2, ver3, false, false, false);

        checkLocal(v1k1, ver1, true, false, false);
        checkLocal(v1k2, ver1, true, false, false);

        entry2.readyLocal(v3k2);

        checkLocal(v3k1, ver3, true, true, false);
        checkLocal(v3k2, ver3, true, true, false);
    }

    /**
     * Tests local candidates with remote version in the middle on key2.
     *
     * @throws Exception If failed.
     */
    public void testReverseOrder4() throws Exception {
        UUID id = UUID.randomUUID();

        GridCacheAdapter<String, String> cache = grid.internalCache();

        GridCacheContext<String, String> ctx = cache.context();

        GridCacheTestEntryEx<String, String> entry1 = new GridCacheTestEntryEx<>(ctx, "1");
        GridCacheTestEntryEx<String, String> entry2 = new GridCacheTestEntryEx<>(ctx, "2");

        GridCacheVersion ver1 = version(1);
        GridCacheVersion ver2 = version(2);
        GridCacheVersion ver3 = version(3);

        // Local with higher version.
        GridCacheMvccCandidate<String> v3k1 = entry1.addLocal(3, ver3, 0, true, false);
        GridCacheMvccCandidate<String> v3k2 = entry2.addLocal(3, ver3, 0, true, false);

        linkCandidates(ctx, v3k1, v3k2);

        entry1.readyLocal(ver3);

        checkLocal(v3k1, ver3, true, true, false);
        checkLocal(v3k2, ver3, false, false, false);

        GridCacheMvccCandidate<String> v1k1 = entry1.addLocal(4, ver1, 0, true, true);
        GridCacheMvccCandidate<String> v1k2 = entry2.addLocal(4, ver1, 0, true, true);

        // Link up.
        linkCandidates(ctx, v1k1, v1k2);

        entry1.readyLocal(ver1);
        entry2.readyLocal(ver1);

        checkLocal(v3k1, ver3, true, true, false); // Owner.
        checkLocal(v3k2, ver3, false, false, false);

        checkLocal(v1k1, ver1, true, false, false);
        checkLocal(v1k2, ver1, true, false, false);

        GridCacheMvccCandidate<String> v2k2 = entry2.addRemote(id, 5, ver2, 0, false, false);

        checkRemote(v2k2, ver2, false, false);

        entry2.readyLocal(v3k2);

        checkLocal(v3k1, ver3, true, true, false);
        checkLocal(v3k2, ver3, true, true, false);
    }

    /**
     * Gets version based on order.
     *
     * @param order Order.
     * @return Version.
     */
    private GridCacheVersion version(int order) {
        return new GridCacheVersion(1, 0, order, order, 0);
    }

    /**
     * Links candidates.
     *
     * @param cands Candidates.
     * @throws Exception If failed.
     */
    private void linkCandidates(final GridCacheContext<String, String> ctx,
        final GridCacheMvccCandidate<String>... cands) throws Exception {
        multithreaded(new Runnable() {
            @Override public void run() {
                for (GridCacheMvccCandidate<String> cand : cands) {
                    boolean b = grid.<String, String>internalCache().context().mvcc().addNext(ctx, cand);

                    assert b;
                }

                info("Link thread finished.");
            }
        }, 1);
    }

    /**
     * Check order in collection.
     *
     * @param cands Candidates to check order for.
     * @param vers Ordered versions.
     */
    private void checkOrder(Collection<GridCacheMvccCandidate<String>> cands, GridCacheVersion... vers) {
        assert cands.size() == vers.length;

        int i = 0;

        for (GridCacheMvccCandidate<String> cand : cands) {
            assert cand.version().equals(vers[i]) : "Invalid order of candidates [cands=" + toString(cands) +
                ", order=" + toString(vers) + ']';

            i++;
        }
    }

    /**
     * @param objs Object to print.
     * @return String representation.
     */
    private String toString(Iterable<?> objs) {
        String eol = System.getProperty("line.separator");

        StringBuilder buf = new StringBuilder(eol);

        for (Object obj : objs) {
            buf.append(obj.toString()).append(eol);
        }

        return buf.toString();
    }

    /**
     * @param objs Object to print.
     * @return String representation.
     */
    private String toString(Object[] objs) {
        String eol = System.getProperty("line.separator");

        StringBuilder buf = new StringBuilder(eol);

        for (Object obj : objs) {
            buf.append(obj.toString()).append(eol);
        }

        return buf.toString();
    }

    /**
     * Checks flags on done candidate.
     *
     * @param cand Candidate to check.
     */
    private void checkDone(GridCacheMvccCandidate<String> cand) {
        assert cand != null;

        info("Done candidate: " + cand);

        assert cand.used();
        assert cand.owner();

        assert !cand.ready();
        assert !cand.reentry();
        assert !cand.local();
    }

    /**
     * @param cand Candidate to check.
     * @param ver Version.
     * @param owner Owner flag.
     * @param used Done flag.
     */
    private void checkRemote(GridCacheMvccCandidate<String> cand, GridCacheVersion ver, boolean owner, boolean used) {
        assert cand != null;

        info("Done candidate: " + cand);

        assert cand.version().equals(ver);

        // Check flags.
        assert cand.used() == used;
        assert cand.owner() == owner;

        assert !cand.ready();
        assert !cand.reentry();
        assert !cand.local();
    }

    /**
     * Checks flags on remote owner candidate.
     *
     * @param cand Candidate to check.
     * @param ver Version.
     */
    private void checkRemoteOwner(GridCacheMvccCandidate<String> cand, GridCacheVersion ver) {
        assert cand != null;

        info("Done candidate: " + cand);

        assert cand.version().equals(ver);

        // Check flags.
        assert cand.used();
        assert cand.owner();

        assert !cand.ready();
        assert !cand.reentry();
        assert !cand.local();
    }

    /**
     * Checks flags on local candidate.
     *
     * @param cand Candidate to check.
     * @param ver Cache version.
     * @param ready Ready flag.
     * @param owner Lock owner.
     * @param reentry Reentry flag.
     */
    private void checkLocal(GridCacheMvccCandidate<String> cand, GridCacheVersion ver, boolean ready,
        boolean owner, boolean reentry) {
        assert cand != null;

        info("Done candidate: " + cand);

        assert cand.version().equals(ver);

        // Check flags.
        assert cand.ready() == ready;
        assert cand.owner() == owner;
        assert cand.reentry() == reentry;

        assert !cand.used();

        assert cand.local();
    }

    /**
     * Checks flags on local candidate.
     *
     * @param cand Candidate to check.
     * @param ver Cache version.
     * @param ready Ready flag.
     * @param owner Lock owner.
     * @param reentry Reentry flag.
     * @param used Used flag.
     */
    private void checkLocal(GridCacheMvccCandidate<String> cand, GridCacheVersion ver, boolean ready,
        boolean owner, boolean reentry, boolean used) {
        assert cand != null;

        info("Done candidate: " + cand);

        assert cand.version().equals(ver);

        // Check flags.
        assert cand.ready() == ready;
        assert cand.owner() == owner;
        assert cand.reentry() == reentry;
        assert cand.used() == used;

        assert cand.local();
    }

    /**
     * Checks flags on local owner candidate.
     *
     * @param cand Candidate to check.
     * @param ver Cache version.
     * @param reentry Reentry flag.
     */
    private void checkLocalOwner(GridCacheMvccCandidate<String> cand, GridCacheVersion ver, boolean reentry) {
        assert cand != null;

        info("Done candidate: " + cand);

        assert cand.version().equals(ver);

        // Check flags.
        assert cand.reentry() == reentry;

        assert !cand.used();

        assert cand.ready();
        assert cand.owner();
        assert cand.local();
    }

    /**
     * @return Empty collection.
     */
    private Collection<GridCacheVersion> empty() {
        return Collections.emptyList();
    }

    /**
     * @param cands Candidates to print.
     */
    private void info(Iterable<GridCacheMvccCandidate<String>> cands) {
        info("Collection of candidates: ");

        for (GridCacheMvccCandidate<String> c : cands) {
            info(">>> " + c);
        }
    }
}
