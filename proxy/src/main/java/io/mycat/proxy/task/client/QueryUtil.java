package io.mycat.proxy.task.client;

import io.mycat.beans.mysql.MySQLCommandType;
import io.mycat.beans.mysql.MySQLSetOption;
import io.mycat.beans.mysql.charset.MySQLCollationIndex;
import io.mycat.logTip.TaskTip;
import io.mycat.proxy.AsyncTaskCallBack;
import io.mycat.proxy.MycatReactorThread;
import io.mycat.proxy.buffer.ProxyBufferImpl;
import io.mycat.proxy.packet.MySQLPacket;
import io.mycat.proxy.session.MySQLClientSession;
import io.mycat.proxy.task.client.resultset.QueryResultSetCollector;
import io.mycat.proxy.task.client.resultset.QueryResultSetTask;
import io.mycat.proxy.task.client.resultset.ResultSetTask;
import io.mycat.proxy.task.client.resultset.TextResultSetTransforCollector;

/**
 * @author jamie12221
 * @date 2019-05-10 21:57 Task请求帮助类
 **/
public interface QueryUtil {

  SetOptionTask SET_OPTION = new SetOptionTask();
  ResultSetTask COMMAND = new ResultSetTask() {

  };

  /**
   * com_query
   */
  static void query(
      MySQLClientSession mysql, String sql,
      AsyncTaskCallBack<MySQLClientSession> callBack) {
    QueryResultSetTask queryResultSetTask = new QueryResultSetTask();
    queryResultSetTask.request(mysql, sql, callBack);
  }

  /**
   * 获取字符集id,结果在回调的result参数
   */
  static void collectCharset(
      MySQLClientSession mysql, MySQLCollationIndex collationIndex,
      AsyncTaskCallBack<MySQLClientSession> callBack) {
    QueryResultSetTask queryResultSetTask = new QueryResultSetTask();
    queryResultSetTask
        .request(mysql, "SELECT id, character_set_name FROM information_schema.collations",
            value -> {
              switch (value) {
                case 0:
                case 1:
                  return true;
                default:
                  return false;
              }
            }, new TextResultSetTransforCollector() {
              int value;

              @Override
              public void addString(int columnIndex, String value) {
                collationIndex.put(this.value, value);

              }

              @Override
              public void addValue(int columnIndex, int value, boolean isNull) {
                this.value = value;
              }
            }, callBack);
  }


  static void showDatabases(MySQLClientSession mysql,
      AsyncTaskCallBack<MySQLClientSession> callback) {
    QueryResultSetTask queryResultSetTask = new QueryResultSetTask();
    queryResultSetTask
        .request(mysql, "show databases;",
            value -> {
              switch (value) {
                case 0:
                  return true;
                default:
                  return false;
              }
            }, new QueryResultSetCollector(), callback);
  }

  static void showTables(MySQLClientSession mysql, String schema,
      AsyncTaskCallBack<MySQLClientSession> callback) {
    QueryResultSetTask queryResultSetTask = new QueryResultSetTask();
    queryResultSetTask
        .request(mysql, "show tables from "
                            + schema
                            + ";",
            value -> {
              switch (value) {
                case 0:
                  return true;
                default:
                  return false;
              }
            }, new QueryResultSetCollector(), callback);
  }

  static void showInformationSchemaColumns(MySQLClientSession mysql
      ,
      AsyncTaskCallBack<MySQLClientSession> callback) {
    QueryResultSetTask queryResultSetTask = new QueryResultSetTask();
    queryResultSetTask
        .request(mysql,
            "SELECT * FROM `information_schema`.`COLUMNS` WHERE TABLE_SCHEMA != 'information_schema' and TABLE_SCHEMA !='performance_schema';",
            value -> {
              return true;
            }, new QueryResultSetCollector(), callback);
  }

  /**
   * 多个修改语句发送
   *
   * @param count 结果数量,对应语句数量
   */
  static void mutilOkResultSet(
      MySQLClientSession mysql, int count, String sql,
      AsyncTaskCallBack<MySQLClientSession> callBack) {
    new MultiUpdateCounterTask(count).request(mysql, sql, callBack);
  }

  /**
   * set option 命令
   */
  static void setOption(
      MySQLClientSession mysql, MySQLSetOption setOption,
      AsyncTaskCallBack<MySQLClientSession> callBack) {
    SET_OPTION.request(mysql, setOption, callBack);
  }

  class SetOptionTask implements ResultSetTask {

    public void request(
        MySQLClientSession mysql, MySQLSetOption setOption,
        AsyncTaskCallBack<MySQLClientSession> callBack) {
      request(mysql, setOption, (MycatReactorThread) Thread.currentThread(), callBack);
    }

    public void request(MySQLClientSession mysql, MySQLSetOption setOption,
        MycatReactorThread curThread, AsyncTaskCallBack<MySQLClientSession> callBack) {

      mysql.setCurrentProxyBuffer(new ProxyBufferImpl(curThread.getBufPool()));
      MySQLPacket mySQLPacket = mysql.newCurrentProxyPacket(7);
      mySQLPacket.writeByte(MySQLCommandType.COM_SET_OPTION);
      mySQLPacket.writeFixInt(2, setOption.getValue());

      try {
        mysql.setCallBack(callBack);
        mysql.switchNioHandler(this);
        mysql.prepareReveiceResponse();
        mysql.writeCurrentProxyPacket(mySQLPacket, mysql.setPacketId(0));
      } catch (Exception e) {
        this.clearAndFinished(mysql, false, mysql.setLastMessage(e.getMessage()));
      }
    }

    @Override
    public void onSocketClosed(MySQLClientSession mysql, boolean normal, String reason) {
      this.clearAndFinished(mysql, false, mysql.setLastMessage(reason));
    }
  }

  class MultiUpdateCounterTask implements ResultSetTask {

    private int counter = 0;

    public MultiUpdateCounterTask(int counter) {
      this.counter = counter;
    }

    public void request(
        MySQLClientSession mysql, String sql,
        AsyncTaskCallBack<MySQLClientSession> callBack) {
      request(mysql, 3, sql, callBack);
    }

    @Override
    public void onColumnDef(MySQLPacket mySQLPacket, int startPos, int endPos) {

    }

    @Override
    public void onTextRow(MySQLPacket mySQLPacket, int startPos, int endPos) {

    }

    @Override
    public void onBinaryRow(MySQLPacket mySQLPacket, int startPos, int endPos) {

    }

    @Override
    public void onFinished(MySQLClientSession mysql, boolean success, String errorMessage) {
      if (counter == 0) {
        AsyncTaskCallBack<MySQLClientSession> callBack = mysql.getCallBackAndReset();
        callBack.finished(mysql, this, true, null, null);
      } else {
        AsyncTaskCallBack<MySQLClientSession> callBack = mysql.getCallBackAndReset();
        callBack.finished(mysql, this, false,
            success ? TaskTip.MULTI_OK_REVEIVE_FAIL.getMessage() : errorMessage, null);
      }
    }

    @Override
    public void onOk(MySQLPacket mySQLPacket, int startPos, int endPos) {
      counter--;
    }

    @Override
    public void onColumnCount(int columnCount) {

    }

    @Override
    public void onSocketClosed(MySQLClientSession mysql, boolean normal, String reason) {
      AsyncTaskCallBack<MySQLClientSession> callBack = mysql.getCallBackAndReset();
      callBack.finished(mysql, this, false, reason, null);
    }
  }

}
