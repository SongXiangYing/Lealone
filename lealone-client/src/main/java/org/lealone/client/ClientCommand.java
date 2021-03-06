/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.async.AsyncHandler;
import org.lealone.async.AsyncResult;
import org.lealone.client.result.ClientResult;
import org.lealone.client.result.RowCountDeterminedClientResult;
import org.lealone.client.result.RowCountUndeterminedClientResult;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.trace.Trace;
import org.lealone.common.util.New;
import org.lealone.db.Command;
import org.lealone.db.CommandParameter;
import org.lealone.db.Session;
import org.lealone.db.SysProperties;
import org.lealone.db.result.Result;
import org.lealone.db.value.Value;
import org.lealone.net.AsyncCallback;
import org.lealone.net.IntAsyncCallback;
import org.lealone.net.Transfer;
import org.lealone.net.VoidAsyncCallback;
import org.lealone.storage.StorageCommand;

/**
 * Represents the client-side part of a SQL statement.
 * This class is not used in embedded mode.
 * 
 * @author H2 Group
 * @author zhh
 */
public class ClientCommand implements StorageCommand {

    private final Transfer transfer;
    private final ArrayList<CommandParameter> parameters;
    private final Trace trace;
    private final String sql;
    private final int fetchSize;
    private boolean prepared;
    private ClientSession session;
    private int id;
    private boolean isQuery;

    public ClientCommand(ClientSession session, Transfer transfer, String sql, int fetchSize) {
        this.transfer = transfer;
        parameters = New.arrayList();
        trace = session.getTrace();
        this.sql = sql;
        this.fetchSize = fetchSize;
        this.session = session;
    }

    @Override
    public Command prepare() {
        prepare(session, true);
        prepared = true;
        return this;
    }

    private void prepare(ClientSession s, boolean createParams) {
        id = s.getNextId();
        try {
            if (createParams) {
                s.traceOperation("COMMAND_PREPARE_READ_PARAMS", id);
                transfer.writeRequestHeader(id, Session.COMMAND_PREPARE_READ_PARAMS);
            } else {
                s.traceOperation("COMMAND_PREPARE", id);
                transfer.writeRequestHeader(id, Session.COMMAND_PREPARE);
            }
            transfer.writeInt(session.getSessionId()).writeString(sql);
            VoidAsyncCallback ac = new VoidAsyncCallback() {
                @Override
                public void runInternal() {
                    try {
                        isQuery = transfer.readBoolean();
                        if (createParams) {
                            parameters.clear();
                            int paramCount = transfer.readInt();
                            for (int j = 0; j < paramCount; j++) {
                                ClientCommandParameter p = new ClientCommandParameter(j);
                                p.readMetaData(transfer);
                                parameters.add(p);
                            }
                        }
                    } catch (IOException e) {
                        throw DbException.convert(e);
                    }
                }
            };
            transfer.addAsyncCallback(id, ac);
            transfer.flush();
            ac.await();
        } catch (IOException e) {
            s.handleException(e);
        }
    }

    @Override
    public boolean isQuery() {
        return isQuery;
    }

    @Override
    public ArrayList<CommandParameter> getParameters() {
        return parameters;
    }

    private void prepareIfRequired() {
        session.checkClosed();
        if (id <= session.getCurrentId() - SysProperties.SERVER_CACHED_OBJECTS) {
            // object is too old - we need to prepare again
            prepare(session, false);
        }
    }

    @Override
    public Result getMetaData() {
        if (!isQuery) {
            return null;
        }
        int objectId = session.getNextId();
        ClientResult result = null;
        prepareIfRequired();
        try {
            session.traceOperation("COMMAND_GET_META_DATA", id);
            transfer.writeRequestHeader(id, Session.COMMAND_GET_META_DATA);
            transfer.writeInt(session.getSessionId()).writeInt(objectId);
            AsyncCallback<ClientResult> ac = new AsyncCallback<ClientResult>() {
                @Override
                public void runInternal() {
                    try {
                        int columnCount = transfer.readInt();
                        int rowCount = transfer.readInt();
                        ClientResult result = new RowCountDeterminedClientResult(session, transfer, objectId,
                                columnCount, rowCount, Integer.MAX_VALUE);

                        setResult(result);
                    } catch (IOException e) {
                        throw DbException.convert(e);
                    }
                }
            };
            transfer.addAsyncCallback(id, ac);
            transfer.flush();
            result = ac.getResult();
        } catch (IOException e) {
            session.handleException(e);
        }
        return result;
    }

    @Override
    public Result executeQuery(int maxRows) {
        return executeQuery(maxRows, false, null, false);
    }

    @Override
    public Result executeQuery(int maxRows, boolean scrollable) {
        return executeQuery(maxRows, scrollable, null, false);
    }

    @Override
    public void executeQueryAsync(int maxRows, boolean scrollable, AsyncHandler<AsyncResult<Result>> handler) {
        executeQuery(maxRows, scrollable, handler, true);
    }

    private Result executeQuery(int maxRows, boolean scrollable, AsyncHandler<AsyncResult<Result>> handler,
            boolean async) {
        if (prepared) {
            checkParameters();
            prepareIfRequired();
        } else {
            id = session.getNextId();
        }
        int resultId = session.getNextId();
        Result result = null;
        try {
            boolean isDistributedQuery = session.getTransaction() != null && !session.getTransaction().isAutoCommit();

            if (prepared) {
                if (isDistributedQuery) {
                    session.traceOperation("COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_QUERY", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_QUERY);
                } else {
                    session.traceOperation("COMMAND_PREPARED_QUERY", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_PREPARED_QUERY);
                }
                transfer.writeInt(session.getSessionId()).writeInt(resultId).writeInt(maxRows);
            } else {
                if (isDistributedQuery) {
                    session.traceOperation("COMMAND_DISTRIBUTED_TRANSACTION_QUERY", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_DISTRIBUTED_TRANSACTION_QUERY);
                } else {
                    session.traceOperation("COMMAND_QUERY", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_QUERY);
                }
                transfer.writeInt(session.getSessionId()).writeString(sql).writeInt(resultId).writeInt(maxRows);
            }
            int fetch;
            if (scrollable) {
                fetch = Integer.MAX_VALUE;
            } else {
                fetch = fetchSize;
            }
            transfer.writeInt(fetch);
            if (prepared)
                sendParameters(transfer);
            result = getQueryResult(isDistributedQuery, fetch, resultId, handler, async);
        } catch (Exception e) {
            session.handleException(e);
        }
        return result;
    }

    private Result getQueryResult(boolean isDistributedQuery, int fetch, int resultId,
            AsyncHandler<AsyncResult<Result>> handler, boolean async) throws IOException {
        isQuery = true;
        AsyncCallback<ClientResult> ac = new AsyncCallback<ClientResult>() {
            @Override
            public void runInternal() {
                try {
                    if (isDistributedQuery)
                        session.getTransaction().addLocalTransactionNames(transfer.readString());

                    int columnCount = transfer.readInt();
                    int rowCount = transfer.readInt();
                    ClientResult result;
                    if (rowCount < 0)
                        result = new RowCountUndeterminedClientResult(session, transfer, resultId, columnCount, fetch);
                    else
                        result = new RowCountDeterminedClientResult(session, transfer, resultId, columnCount, rowCount,
                                fetch);

                    session.readSessionState();
                    setResult(result);
                    if (handler != null) {
                        AsyncResult<Result> r = new AsyncResult<>();
                        r.setResult(result);
                        handler.handle(r);
                    }
                } catch (IOException e) {
                    throw DbException.convert(e);
                }
            }
        };
        transfer.addAsyncCallback(id, ac);
        transfer.flush();

        if (async) {
            return null;
        } else {
            Result result = ac.getResult();
            session.readSessionState();
            return result;
        }
    }

    @Override
    public int executeUpdate() {
        return executeUpdate(null, null, false);
    }

    @Override
    public int executeUpdate(String replicationName) {
        return executeUpdate(replicationName, null, false);
    }

    @Override
    public void executeUpdateAsync(AsyncHandler<AsyncResult<Integer>> handler) {
        executeUpdate(null, handler, true);
    }

    private int executeUpdate(String replicationName, AsyncHandler<AsyncResult<Integer>> handler, boolean async) {
        if (prepared) {
            checkParameters();
            prepareIfRequired();
        } else {
            id = session.getNextId();
        }
        int updateCount = 0;
        try {
            boolean isDistributedUpdate = session.getTransaction() != null && !session.getTransaction().isAutoCommit();

            if (prepared) {
                if (isDistributedUpdate) {
                    session.traceOperation("COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_UPDATE", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_DISTRIBUTED_TRANSACTION_PREPARED_UPDATE);
                } else if (replicationName != null) {
                    session.traceOperation("COMMAND_REPLICATION_PREPARED_UPDATE", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_REPLICATION_PREPARED_UPDATE);
                } else {
                    session.traceOperation("COMMAND_PREPARED_UPDATE", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_PREPARED_UPDATE);
                }
            } else {
                if (isDistributedUpdate) {
                    session.traceOperation("COMMAND_DISTRIBUTED_TRANSACTION_UPDATE", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_DISTRIBUTED_TRANSACTION_UPDATE);
                } else if (replicationName != null) {
                    session.traceOperation("COMMAND_REPLICATION_UPDATE", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_REPLICATION_UPDATE);
                } else {
                    session.traceOperation("COMMAND_UPDATE", id);
                    transfer.writeRequestHeader(id, Session.COMMAND_UPDATE);
                }
            }
            transfer.writeInt(session.getSessionId());
            if (!prepared)
                transfer.writeString(sql);
            if (replicationName != null)
                transfer.writeString(replicationName);

            if (prepared)
                sendParameters(transfer);

            updateCount = getUpdateCount(isDistributedUpdate, id, handler, async);
        } catch (Exception e) {
            session.handleException(e);
        }
        return updateCount;
    }

    private int getUpdateCount(boolean isDistributedUpdate, int id, AsyncHandler<AsyncResult<Integer>> handler,
            boolean async) throws IOException {
        isQuery = false;
        IntAsyncCallback ac = new IntAsyncCallback() {
            @Override
            public void runInternal() {
                try {
                    if (isDistributedUpdate)
                        session.getTransaction().addLocalTransactionNames(transfer.readString());

                    int updateCount = transfer.readInt();
                    setResult(updateCount);
                    session.readSessionState();
                    if (handler != null) {
                        AsyncResult<Integer> r = new AsyncResult<>();
                        r.setResult(updateCount);
                        handler.handle(r);
                    }
                } catch (IOException e) {
                    throw DbException.convert(e);
                }
            }
        };
        transfer.addAsyncCallback(id, ac);
        transfer.flush();

        int updateCount;
        if (async) {
            updateCount = -1;
        } else {
            ac.await();
            session.readSessionState();
            updateCount = ac.getResult();
        }

        return updateCount;
    }

    private void checkParameters() {
        for (CommandParameter p : parameters) {
            p.checkSet();
        }
    }

    private void sendParameters(Transfer transfer) throws IOException {
        int len = parameters.size();
        transfer.writeInt(len);
        for (CommandParameter p : parameters) {
            transfer.writeValue(p.getValue());
        }
    }

    @Override
    public void close() {
        if (session == null || session.isClosed()) {
            return;
        }
        session.traceOperation("COMMAND_CLOSE", id);
        try {
            transfer.writeRequestHeader(id, Session.COMMAND_CLOSE).flush();
        } catch (IOException e) {
            trace.error(e, "close");
        }
        session = null;
        try {
            for (CommandParameter p : parameters) {
                Value v = p.getValue();
                if (v != null) {
                    v.close();
                }
            }
        } catch (DbException e) {
            trace.error(e, "close");
        }
        parameters.clear();
    }

    /**
     * Cancel this current statement.
     */
    @Override
    public void cancel() {
        session.cancelStatement(id);
    }

    @Override
    public String toString() {
        return sql + Trace.formatParams(getParameters());
    }

    @Override
    public int getType() {
        return CLIENT_COMMAND;
    }

    int getId() {
        return id;
    }

    String getSql() {
        return sql;
    }

    @Override
    public Object executePut(String replicationName, String mapName, ByteBuffer key, ByteBuffer value) {
        byte[] bytes = null;
        int id = session.getNextId();
        try {
            boolean isDistributedUpdate = session.getTransaction() != null && !session.getTransaction().isAutoCommit();
            if (isDistributedUpdate) {
                session.traceOperation("COMMAND_STORAGE_DISTRIBUTED_PUT", id);
                transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_DISTRIBUTED_PUT);
            } else if (replicationName != null) {
                session.traceOperation("COMMAND_STORAGE_REPLICATION_PUT", id);
                transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_REPLICATION_PUT);
            } else {
                session.traceOperation("COMMAND_STORAGE_PUT", id);
                transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_PUT);
            }
            transfer.writeInt(session.getSessionId()).writeString(mapName).writeByteBuffer(key).writeByteBuffer(value);
            if (replicationName != null)
                transfer.writeString(replicationName);
            transfer.flush();

            if (isDistributedUpdate)
                session.getTransaction().addLocalTransactionNames(transfer.readString());

            bytes = transfer.readBytes();
        } catch (Exception e) {
            session.handleException(e);
        }
        session.readSessionState();
        return bytes;
    }

    @Override
    public Object executeGet(String mapName, ByteBuffer key) {
        byte[] bytes = null;
        int id = session.getNextId();
        try {
            boolean isDistributedUpdate = session.getTransaction() != null && !session.getTransaction().isAutoCommit();
            if (isDistributedUpdate) {
                session.traceOperation("COMMAND_STORAGE_DISTRIBUTED_GET", id);
                transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_DISTRIBUTED_GET);
            } else {
                session.traceOperation("COMMAND_STORAGE_GET", id);
                transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_GET);
            }
            transfer.writeInt(session.getSessionId()).writeString(mapName).writeByteBuffer(key);
            transfer.flush();

            if (isDistributedUpdate)
                session.getTransaction().addLocalTransactionNames(transfer.readString());

            bytes = transfer.readBytes();
        } catch (Exception e) {
            session.handleException(e);
        }
        session.readSessionState();
        return bytes;
    }

    @Override
    public void moveLeafPage(String mapName, ByteBuffer splitKey, ByteBuffer page) {
        int id = session.getNextId();
        try {
            session.traceOperation("COMMAND_STORAGE_MOVE_LEAF_PAGE", id);
            transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_MOVE_LEAF_PAGE);
            transfer.writeInt(session.getSessionId());
            transfer.writeString(mapName).writeByteBuffer(splitKey).writeByteBuffer(page);
            transfer.flush();
        } catch (Exception e) {
            session.handleException(e);
        }
        session.readSessionState();
    }

    @Override
    public void removeLeafPage(String mapName, ByteBuffer key) {
        int id = session.getNextId();
        try {
            session.traceOperation("COMMAND_STORAGE_REMOVE_LEAF_PAGE", id);
            transfer.writeRequestHeader(id, Session.COMMAND_STORAGE_REMOVE_LEAF_PAGE);
            transfer.writeInt(session.getSessionId()).writeString(mapName).writeByteBuffer(key);
            transfer.flush();
        } catch (Exception e) {
            session.handleException(e);
        }
        session.readSessionState();
    }

    /**
     * A client side parameter.
     */
    private static class ClientCommandParameter implements CommandParameter {

        private final int index;
        private Value value;
        private int dataType = Value.UNKNOWN;
        private long precision;
        private int scale;
        private int nullable = ResultSetMetaData.columnNullableUnknown;

        public ClientCommandParameter(int index) {
            this.index = index;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public void setValue(Value newValue, boolean closeOld) {
            if (closeOld && value != null) {
                value.close();
            }
            value = newValue;
        }

        @Override
        public void setValue(Value value) {
            this.value = value;
        }

        @Override
        public Value getValue() {
            return value;
        }

        @Override
        public void checkSet() {
            if (value == null) {
                throw DbException.get(ErrorCode.PARAMETER_NOT_SET_1, "#" + (index + 1));
            }
        }

        @Override
        public boolean isValueSet() {
            return value != null;
        }

        @Override
        public int getType() {
            return value == null ? dataType : value.getType();
        }

        @Override
        public long getPrecision() {
            return value == null ? precision : value.getPrecision();
        }

        @Override
        public int getScale() {
            return value == null ? scale : value.getScale();
        }

        @Override
        public int getNullable() {
            return nullable;
        }

        /**
         * Read the parameter meta data from the transfer object.
         *
         * @param transfer the transfer object
         */
        public void readMetaData(Transfer transfer) throws IOException {
            dataType = transfer.readInt();
            precision = transfer.readLong();
            scale = transfer.readInt();
            nullable = transfer.readInt();
        }

    }

}
