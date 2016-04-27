/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.database.tree.reuse;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.pagemem.FullPageId;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.processors.cache.database.MetaStore;
import org.apache.ignite.internal.processors.cache.database.tree.BPlusTree;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.lang.IgniteBiTuple;

/**
 * Reuse list for index pages.
 */
public final class ReuseList {
    /** */
    private static final FullPageId MIN = new FullPageId(0,0);

    /** */
    private final ReuseTree[] trees;

    /**
     * @param cacheId Cache ID.
     * @param pageMem Page memory.
     * @param segments Segments.
     * @param metaStore Meta store.
     * @throws IgniteCheckedException If failed.
     */
    public ReuseList(int cacheId, PageMemory pageMem, int segments, MetaStore metaStore) throws IgniteCheckedException {
        A.ensure(segments > 1, "Segments must be greater than 1.");

        ReuseTree[] trees0 = new ReuseTree[segments];

        for (int i = 0; i < segments; i++) {
            String idxName = i + "##" + cacheId + "_reuse";

            IgniteBiTuple<FullPageId,Boolean> t = metaStore.getOrAllocateForIndex(cacheId, idxName);

            trees0[i] = new ReuseTree(this, cacheId, pageMem, t.get1(), t.get2());
        }

        // Later assignment is done intentionally, see null check in method take.
        trees = trees0;
    }

    /**
     * @return Size.
     * @throws IgniteCheckedException If failed.
     */
    public long size() throws IgniteCheckedException {
        long size = 0;

        for (ReuseTree tree : trees)
            size += tree.size();

        return size;
    }

    /**
     * @param client Client.
     * @return Reuse tree.
     */
    private ReuseTree tree(BPlusTree<?,?> client) {
        int treeIdx = client.randomInt(trees.length);

        ReuseTree tree = trees[treeIdx];

        assert tree != null;

        // Avoid recursion on the same tree.
        if (tree == client) {
            treeIdx++; // Go forward and take the next tree.

            if (treeIdx == trees.length)
                treeIdx = 0;

            tree = trees[treeIdx];
        }

        return tree;
    }

    /**
     * @param client Client tree.
     * @param bag Reuse bag.
     * @return Page ID.
     * @throws IgniteCheckedException If failed.
     */
    public FullPageId take(BPlusTree<?,?> client, ReuseBag bag) throws IgniteCheckedException {
        if (trees == null)
            return null;

        ReuseTree tree = tree(client);

        // Remove and return page at min possible position.
        FullPageId res = tree.removeCeil(MIN, bag);

        assert res != null || tree.size() == 0; // TODO remove

        return res;
    }

    /**
     * @param client Client tree.
     * @param bag Reuse bag.
     * @throws IgniteCheckedException If failed.
     */
    public void add(BPlusTree<?,?> client, ReuseBag bag) throws IgniteCheckedException {
        if (bag == null)
            return;

        ReuseTree tree = tree(client);

        for (;;) {
            FullPageId pageId = bag.pollFreePage();

            if (pageId == null)
                break;

            tree.put(pageId, bag);
        }
    }
}
