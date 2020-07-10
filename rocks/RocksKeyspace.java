/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.rocks;

import grakn.Grakn;
import grakn.common.exception.GraknException;
import grakn.graph.util.KeyGenerator;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.RocksDBException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;

public class RocksKeyspace implements Grakn.Keyspace {

    private final String name;
    private final RocksGrakn core;
    private final OptimisticTransactionDB rocksDB;
    private final KeyGenerator.Persisted keyGenerator;
    private final ConcurrentMap<RocksSession, Long> sessions;
    private final StampedLock schemaLock;
    private final AtomicBoolean isOpen;

    private RocksKeyspace(RocksGrakn core, String name) {
        this.name = name;
        this.core = core;
        keyGenerator = new KeyGenerator.Persisted();
        sessions = new ConcurrentHashMap<>();
        isOpen = new AtomicBoolean(false);
        schemaLock = new StampedLock();

        try {
            rocksDB = OptimisticTransactionDB.open(this.core.rocksOptions(), directory().toString());
        } catch (RocksDBException e) {
            throw new GraknException(e);
        }
    }

    static RocksKeyspace createNewAndOpen(RocksGrakn core, String name) {
        return new RocksKeyspace(core, name).initialiseAndOpen();
    }

    static RocksKeyspace loadExistingAndOpen(RocksGrakn core, String name) {
        return new RocksKeyspace(core, name).loadAndOpen();
    }

    private RocksKeyspace initialiseAndOpen() {
        try (RocksSession session = createAndOpenSession(Grakn.Session.Type.SCHEMA)) {
            try (RocksTransaction txn = session.transaction(Grakn.Transaction.Type.WRITE)) {
                if (txn.graph().isInitialised()) {
                    throw new GraknException("Invalid Keyspace Initialisation");
                }
                txn.graph().initialise();
                txn.commit();
            }
        }
        isOpen.set(true);
        return this;
    }

    private RocksKeyspace loadAndOpen() {
        try (RocksSession session = createAndOpenSession(Grakn.Session.Type.DATA)) {
            try (RocksTransaction txn = session.transaction(Grakn.Transaction.Type.READ)) {
                keyGenerator.sync(txn.storage());
            }
        }
        isOpen.set(true);
        return this;
    }

    RocksSession createAndOpenSession(Grakn.Session.Type type) {
        long schemaWriteLockStamp = 0;
        if (type.equals(Grakn.Session.Type.SCHEMA)) {
            schemaWriteLockStamp = schemaLock.writeLock();
        }
        RocksSession session = new RocksSession(this, type);
        sessions.put(session, schemaWriteLockStamp);
        return session;
    }

    private Path directory() {
        return core.directory().resolve(name);
    }

    OptimisticTransactionDB rocks() {
        return rocksDB;
    }

    KeyGenerator keyGenerator() {
        return keyGenerator;
    }

    long acquireSchemaReadLock() {
        return schemaLock.readLock();
    }

    void releaseSchemaReadLock(long stamp) {
        schemaLock.unlockRead(stamp);
    }

    void remove(RocksSession session) {
        long schemaWriteLockStamp = sessions.remove(session);
        if (session.type().equals(Grakn.Session.Type.SCHEMA)) {
            schemaLock.unlockWrite(schemaWriteLockStamp);
        }
    }

    void close() {
        if (isOpen.compareAndSet(true, false)) {
            sessions.keySet().forEach(RocksSession::close);
            rocksDB.close();
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void delete() {
        close();
        core.keyspaces().remove(this);
        try {
            Files.walk(directory()).sorted(Comparator.reverseOrder())
                    .map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            throw new GraknException(e);
        }
    }
}
