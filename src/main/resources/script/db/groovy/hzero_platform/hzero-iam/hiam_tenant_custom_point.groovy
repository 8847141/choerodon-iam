package script.db

databaseChangeLog(logicalFilePath: 'script/db/hiam_tenant_custom_point.groovy') {
    changeSet(author: "hzero", id: "2019-12-16-hiam_tenant_custom_point") {
        def weight = 1
        if(helper.isSqlServer()){
            weight = 2
        } else if(helper.isOracle()){
            weight = 3
        }
        if(helper.dbType().isSupportSequence()){
            createSequence(sequenceName: 'hiam_tenant_custom_point_s', startValue:"1")
        }
        createTable(tableName: "hiam_tenant_custom_point", remarks: "租户客户化端点关系") {
            column(name: "tenant_custom_point_id", type: "bigint(20)", autoIncrement: true ,   remarks: "表ID，主键，供其他表做外键")  {constraints(primaryKey: true)} 
            column(name: "tenant_id", type: "bigint(20)",  remarks: "租户ID")  {constraints(nullable:"false")}  
            column(name: "custom_point_code", type: "varchar(" + 120 * weight + ")",  remarks: "客户化端点编码，hiam_custom_point.code")  {constraints(nullable:"false")}  
            column(name: "object_version_number", type: "bigint(20)",   defaultValue:"1",   remarks: "行版本号，用来处理锁")  {constraints(nullable:"false")}  
            column(name: "creation_date", type: "datetime",   defaultValueComputed:"CURRENT_TIMESTAMP",   remarks: "")  {constraints(nullable:"false")}  
            column(name: "created_by", type: "bigint(20)",   defaultValue:"-1",   remarks: "")  {constraints(nullable:"false")}  
            column(name: "last_updated_by", type: "bigint(20)",   defaultValue:"-1",   remarks: "")  {constraints(nullable:"false")}  
            column(name: "last_update_date", type: "datetime",   defaultValueComputed:"CURRENT_TIMESTAMP",   remarks: "")  {constraints(nullable:"false")}  

        }

        addUniqueConstraint(columnNames:"tenant_id,custom_point_code",tableName:"hiam_tenant_custom_point",constraintName: "hiam_tenant_custom_point_u1")
    }
}