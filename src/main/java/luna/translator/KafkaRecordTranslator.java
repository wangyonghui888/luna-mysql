package luna.translator;

import com.google.common.collect.Lists;
import luna.common.AbstractLifeCycle;
import luna.common.context.MysqlContext;
import luna.common.model.meta.ColumnMeta;
import luna.common.model.meta.ColumnValue;
import luna.common.model.meta.TableMeta;
import luna.common.model.OperateType;
import luna.common.model.Record;
import luna.common.model.SchemaTable;
import luna.applier.MysqlApplier;
import luna.exception.LunaException;
import luna.util.TimeUtil;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaRecordTranslator extends AbstractLifeCycle implements Translator {
    private MysqlContext    mysqlContext;
    private MysqlApplier    mysqlApplier;

    public KafkaRecordTranslator(MysqlContext mysqlContext, MysqlApplier mysqlApplier){
        this.mysqlContext = mysqlContext;
        this.mysqlApplier = mysqlApplier;
    }

    public void start(){
        super.start();
        logger.info("KafkaRecordTranslator is started!");
    }

    public void stop(){
        super.stop();
        logger.info("KafkaRecordTranslator is stopped!");
    }


    public void translateBatch(List<Map<String,Object>> records){
        if(records.isEmpty()){
            return;
        }
        Map<SchemaTable,List<Record>> recordsBucket = new HashMap<>();
        for(Map<String,Object> payload: records){
            Record record=translateToRecord(payload);
            SchemaTable schemaTable = new SchemaTable(record.getSchema(),record.getTable());
            if(recordsBucket.get(schemaTable)==null){
                List<Record> recordsNew=Lists.newArrayList();
                recordsNew.add(record);
                recordsBucket.put(schemaTable,recordsNew);
            }else{
                recordsBucket.get(schemaTable).add(record);
            }
        }
        recordsBucket.forEach(((schemaTable, myRecords) -> {
            mysqlApplier.applyBatch(myRecords,schemaTable);
        }));
    }

    public void translate(Map<String, Object> payload){
        Record record=translateToRecord(payload);
        String tableName = (String) payload.get("table");
        Map<String,Object> recordPayload = (Map<String, Object>) payload.get("data");
        String modify_time = (String) recordPayload.get("modify_time");
        long modifyTimeMillis = 0;
        try {
            modifyTimeMillis = TimeUtil.stringToLong(modify_time, "yy-MM-dd HH:mm:ss.SSS");
        }catch (ParseException e){
            errorLog.error(ExceptionUtils.getFullStackTrace(e));
        }
        long before = System.currentTimeMillis();
        mysqlApplier.apply(record);
        long after = System.currentTimeMillis();
        timeLog.info("" + tableName + " " + (after-modifyTimeMillis) + " " + (after-before));
    }

    private Record translateToRecord(Map<String, Object> payload){
        String type = (String) payload.get("type");
        String schema = (String) payload.get("database");
        String tableName = (String) payload.get("table");
        Map<String,Object> recordPayload = (Map<String, Object>) payload.get("data");
        SchemaTable schemaTable = new SchemaTable(schema,tableName);
        TableMeta tableMeta = mysqlContext.getTableMetas().get(schemaTable);
        int splitColumnValue = fourSplitValue(String.valueOf(recordPayload.get(tableMeta.getExtKey())));
        int targetNum = splitColumnValue%tableMeta.getExtNum();
        String targetSchema = schema+targetNum;
        String targetTable = tableName;
        Record record = new Record(targetSchema,targetTable,tableMeta.getPrimaryKey(),getOpType(type));
        recordPayload.forEach((columnName,columnValue)->{
            ColumnMeta columnMeta=tableMeta.getColumnMeta(columnName);
            ColumnValue column = new ColumnValue(columnMeta,columnValue);
            record.addColumn(column);
        });
        return record;
    }

    private int fourSplitValue(String value){
        int fourValue=0;
        for (int i = 1; i <= 4 && value.length() - i >= 0; i++) {
            char digit = value.charAt(value.length() - i);
            if(Character.isDigit(digit)) {
                fourValue = fourValue + Character.getNumericValue(digit) * (int) Math.pow(10, i - 1);
            }else{
                throw new LunaException("Can not parse the last four digit of "+value);
            }
        }
        return fourValue;
    }

    private OperateType getOpType(String type){
        switch (type){
            case "insert":
                return OperateType.I;
            case "update":
                return OperateType.U;
            case "delete":
                return OperateType.D;
            default:
                throw new LunaException("Unknown operation type!");
        }

    }
}
