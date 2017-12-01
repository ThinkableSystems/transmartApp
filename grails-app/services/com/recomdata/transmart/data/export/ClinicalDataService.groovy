package com.recomdata.transmart.data.export

import com.recomdata.dataexport.util.ExportUtil
import com.recomdata.transmart.data.export.util.ClinicalMRNAPivotDataConverter
import com.recomdata.transmart.data.export.util.ClinicalPivotDataConverter
import com.recomdata.transmart.data.export.util.FileWriterUtil
import com.recomdata.transmart.util.UtilService

import grails.util.Holders
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic
import org.apache.commons.lang.StringUtils
import org.codehaus.groovy.ant.Groovy
import org.codehaus.groovy.tools.GroovyClass
import org.hibernate.Query
import org.hibernate.ScrollMode
import org.hibernate.ScrollableResults
import org.rosuda.REngine.REXP
import org.rosuda.REngine.Rserve.RConnection
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy

import javax.sql.DataSource
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.sql.ResultSet
import java.sql.Statement

import static org.transmart.authorization.QueriesResourceAuthorizationDecorator.checkQueryResultAccess

//@CompileStatic
class ClinicalDataService {

    boolean transactional = true

    DataSource dataSource
    def i2b2HelperService
    def springSecurityService
    UtilService utilService = new UtilService()


    // This reflection for the I2b2HelperService scope expansion
    Class i2b2HelperServiceClass = Class.forName("I2b2HelperService");
    Method getConceptCodeFromKeyMethod = i2b2HelperServiceClass.getDeclaredMethod("getConceptCodeFromKey", String.class);


    //Logger log = Logger.getLogger(getClass()) // log4j

    //This is the SQL query we use to get our data.
    //private StringBuilder sqlQuery = new StringBuilder();

    //This is the list of parameters passed to the SQL statement.
    //def parameterList = null;

    boolean dataFound = false

    /**
     * This method will gather data from the i2b2 database and write it to a file. The file will contain PATIENT_NUM,CONCEPT_PATH, The concept name and a subset.
     * @param fileName Name of the data file.
     * @param result_instance_ids A hashmap of the form ["subset1":result_instance_id]
     * @param conceptCodeList An array of strings representing the concept codes to filter on.
     * @return
     */
    public boolean getData(List studyList, File studyDir, String fileName, String jobName, String resultInstanceId,
                           String[] conceptCodeList, List retrievalTypes, boolean parPivotData, boolean parFilterHighLevelConcepts,
                           Map snpFilesMap, String subset, Map filesDoneMap, List platformsList, String[] parentConceptCodeList, Boolean includeConceptContext) {

        checkQueryResultAccess resultInstanceId

        def sqlQuery = new StringBuilder();
        def parameterList = null;

        boolean retrievalTypeMRNAExists = retrievalTypeExists('MRNA', retrievalTypes)
        boolean retrievalTypeSNPExists = retrievalTypeExists('SNP', retrievalTypes)
        Boolean includeParentInfo = false

        if (null != resultInstanceId) {
            //Construct the SQL Query.
            sqlQuery <<= 'SELECT ofa.PATIENT_NUM, cd.CONCEPT_PATH, cd.CONCEPT_CD, cd.NAME_CHAR, '
            sqlQuery <<= 'case ofa.VALTYPE_CD '
            sqlQuery <<= ' WHEN \'T\' THEN TVAL_CHAR '
            sqlQuery <<= ' WHEN \'N\' THEN CAST(NVAL_NUM AS varchar(30)) '
            sqlQuery <<= 'END as VALUE, ? SUBSET , pd.sourcesystem_cd, ed.days_since_enroll '

            //If we are going to union in the codes that have parent concepts, we include the parent columns here too.
            if (parentConceptCodeList.size() > 0) {
                sqlQuery <<= ', \'\' AS PARENT_PATH,\'\' AS  PARENT_CODE '
            }

            //If we are including the concepts context, add the columns to the statement here.
            if (includeConceptContext) {
                sqlQuery <<= ', DC.DE_CONTEXT_NAME '
            }

            if (retrievalTypeMRNAExists && null != filesDoneMap['MRNA.TXT'] && filesDoneMap['MRNA.TXT']) {
                sqlQuery <<= ', ssm.assay_id, ssm.sample_type, ssm.timepoint, ssm.tissue_type '
            }

            sqlQuery <<= 'FROM qt_patient_set_collection qt '
            sqlQuery <<= 'INNER JOIN OBSERVATION_FACT ofa ON qt.PATIENT_NUM = ofa.PATIENT_NUM '
            sqlQuery <<= 'LEFT OUTER JOIN DE_OBS_ENROLL_DAYS ed on ofa.encounter_num = ed.encounter_num '

            //If we are including the concepts context, add the tables to the statement here.
            if (includeConceptContext) {
                sqlQuery <<= ' LEFT JOIN DEAPP.DE_CONCEPT_CONTEXT DCC ON DCC.CONCEPT_CD = ofa.CONCEPT_CD '
                sqlQuery <<= ' LEFT JOIN DEAPP.DE_CONTEXT DC ON DC.DE_CONTEXT_ID = DCC.DE_CONTEXT_ID '
            }

            sqlQuery <<= 'INNER JOIN CONCEPT_DIMENSION cd ON cd.CONCEPT_CD = ofa.CONCEPT_CD '
            sqlQuery <<= 'INNER JOIN PATIENT_DIMENSION pd on ofa.patient_num = pd.patient_num '

            if (retrievalTypeMRNAExists && null != filesDoneMap['MRNA.TXT'] && filesDoneMap['MRNA.TXT']) {
                sqlQuery <<= 'LEFT JOIN DE_SUBJECT_SAMPLE_MAPPING ssm ON ssm.PATIENT_ID = ofa.PATIENT_NUM  '
            }

            sqlQuery <<= 'WHERE qt.RESULT_INSTANCE_ID = ?'

            if (!retrievalTypeMRNAExists && parFilterHighLevelConcepts) {
                sqlQuery <<= ' AND cd.concept_cd NOT IN (SELECT DISTINCT coalesce(sample_type_cd,\'-1\') as gene_expr_concept'
                sqlQuery <<= ' FROM de_subject_sample_mapping WHERE trial_name = ?'
                sqlQuery <<= ' UNION SELECT DISTINCT coalesce(tissue_type_cd,\'-1\') as gene_expr_concept '
                sqlQuery <<= ' FROM de_subject_sample_mapping WHERE trial_name = ?'
                sqlQuery <<= ' UNION SELECT DISTINCT coalesce(platform_cd,\'-1\') as gene_expr_concept '
                sqlQuery <<= ' FROM de_subject_sample_mapping WHERE trial_name = ?)'
            }

            if (retrievalTypeMRNAExists && null != filesDoneMap && filesDoneMap['MRNA.TXT'] && !platformsList?.isEmpty()) {
                sqlQuery <<= ' AND ssm.GPL_ID IN (' << utilService.toListString(platformsList) << ') '

            }

            //If we have a list of concepts, add them to the query.
            if (conceptCodeList.size() > 0) sqlQuery <<= ' AND cd.CONCEPT_CD IN (' + quoteCSV(conceptCodeList.join(',')) + ') '

            //If we have the parent codes, add the UNION to bring in the child concepts.
            if (parentConceptCodeList.size() > 0) {
                includeParentInfo = true

                sqlQuery <<= getParentConceptUnion(parentConceptCodeList, includeConceptContext)
            }

            sqlQuery <<= ' ORDER BY pd.sourcesystem_cd, cd.CONCEPT_PATH '

        }

        for (int i = 0; i < studyList.size(); i++) {
            parameterList = new ArrayList();
            //Add the name of the subset to the parameter list.
            parameterList.add(subset)
            //Add the value of the result instance ID to the parameter list.
            parameterList.add(resultInstanceId)

            if (!retrievalTypeMRNAExists && parFilterHighLevelConcepts) {
                parameterList.add(studyList.get(i))
                parameterList.add(studyList.get(i))
                parameterList.add(studyList.get(i))
            }

            //Add the parameters for the UNION with parent codes.
            if (parentConceptCodeList.size() > 0) {
                parameterList.add(subset)
                parameterList.add(resultInstanceId)

                //We need to get the concept code for this path.
                String parentConceptCode = (String)getConceptCodeFromKeyMethod.invoke(i2b2HelperServiceClass.newInstance(), "\\\\" + parentConceptCodeList[0].trim())


                //The only use case we are concerned about for now is the case of one parent concept.
                parameterList.add(parentConceptCode)
            }

            def filename = (studyList?.size() > 1) ? (studyList.get(i) as String) + '_' + fileName : fileName
            log.debug("Retrieving Clinical data : " + sqlQuery)
            log.debug("Retrieving Clinical data : " + parameterList)

            //Only pivot the data if the parameter specifies it.
            if (parPivotData) {
                boolean mRNAExists = retrievalTypeMRNAExists && null != filesDoneMap['MRNA.TXT'] && filesDoneMap['MRNA.TXT']
                boolean snpExists = retrievalTypeSNPExists && null != filesDoneMap['SNP.PED, .MAP & .CNV'] && filesDoneMap['SNP.PED, .MAP & .CNV']

                pivotData((studyList?.size() > 1), studyList.get(i) as String,
                        writeData(sqlQuery, parameterList, studyDir, filename, jobName, retrievalTypes, snpFilesMap),
                        mRNAExists, snpExists)
            } else {
                writeData(sqlQuery, parameterList, studyDir, filename, jobName, retrievalTypes, null, includeParentInfo, includeConceptContext)
            }
        }


        return dataFound
    }



    private String writeData(StringBuilder sqlQuery, List parameterList, File studyDir, String fileName, String jobName, List retrievalTypes, Map snpFilesMap = null, Boolean includeParentInfo = false, Boolean includeConceptContext = false) {
        //TODO Get the dataTypeName from the list of DataTypeNames either from DB or from config file
        String dataTypeName = "Clinical";
        //TODO set this to either "Raw_Files/Findings" or NULL for processed_files
        String dataTypeFolder = null;
        //Build the query to get the clinical data.
        groovy.sql.Sql sql = new groovy.sql.Sql(dataSource)
        char separator = '\t';
        def filePath = null
        FileWriterUtil writerUtil = null

        try {
            dataFound = false

            log.debug('Clinical Data Query :: ' + sqlQuery.toString())

            sql.withStatement {Statement stmt ->
                stmt.fetchSize = 2000
            }

            log.debug('Writing Clinical File')
            writerUtil = new FileWriterUtil(studyDir, fileName, jobName, dataTypeName, dataTypeFolder, separator as char)
            writerUtil.writeLine(getColumnNames(retrievalTypes, snpFilesMap, includeParentInfo, includeConceptContext) as String[])


            sql.eachRow(sqlQuery.toString(), parameterList) { row ->

                dataFound = true
                def values = []
                //values.add(rows.get(i).PATIENT_NUM?.toString())
                values.add(utilService.getActualPatientId(row.SOURCESYSTEM_CD?.toString()))
                values.add(row.SUBSET?.toString())
                values.add(row.CONCEPT_CD?.toString())

                //Add Concept Path
                def removalArr = [row.VALUE]


                if (retrievalTypeExists("MRNA", retrievalTypes)) {
                    removalArr.add(row.TISSUE_TYPE?.toString())
                    removalArr.add(row.TIMEPOINT?.toString())
                    removalArr.add(row.SAMPLE_TYPE?.toString())
                }
                if (removalArr.size() == 1 && row.VALUE?.toString().equalsIgnoreCase('E')) {
                    removalArr.add(row.NAME_CHAR?.toString())
                }
                values.add(ExportUtil.getShortConceptPath((String)row.CONCEPT_PATH, removalArr))

                if (retrievalTypeExists("MRNA", retrievalTypes)) {
                    values.add(ExportUtil.getSampleValue((String)row.VALUE, (String)row.SAMPLE_TYPE, (String)row.TIMEPOINT, (String)row.TISSUE_TYPE))
                } else {
                    if (row.VALUE?.toString().equalsIgnoreCase('E')) {
                        values.add(row.NAME_CHAR?.toString())
                    } else {
                        values.add(row.VALUE?.toString())
                    }
                }

                //Actual Concept Path is required for Data Association
                values.add(row.CONCEPT_PATH)

                //NOD Data field
                values.add(row.DAYS_SINCE_ENROLL);

                if (retrievalTypeExists("MRNA", retrievalTypes)) {
                    values.add(row.ASSAY_ID?.toString())
                }

                if (retrievalTypeExists("SNP", retrievalTypes)) {
                    Map PEDFilesMap = (Map)snpFilesMap?.get("PEDFiles")
                    def pedFile = PEDFilesMap?.get(row.PATIENT_NUM?.toString() + '_' + row.CONCEPT_CD?.toString())
                    if (null != snpFilesMap?.get("PEDFiles")) {
                        if (StringUtils.isNotEmpty((String)pedFile)) {
                            values.add(pedFile?.toString())
                        } else {
                            values.add("")
                        }
                    }

                    Map MAPFilesMap = (Map)snpFilesMap?.get("MAPFiles")
                    def mapFile = MAPFilesMap?.get(row.PATIENT_NUM?.toString() + '_' + row.CONCEPT_CD?.toString())
                    if (null != snpFilesMap?.get("MAPFiles")) {
                        if (StringUtils.isNotEmpty((String)mapFile)) {
                            values.add(mapFile?.toString())
                        } else {
                            values.add("")
                        }
                    }
                }

                if (includeParentInfo) {
                    values.add(row.PARENT_PATH?.toString())
                    values.add(row.PARENT_CODE?.toString())
                }

                if (includeConceptContext) {
                    values.add(row.DE_CONTEXT_NAME?.toString())
                }

                writerUtil.writeLine(values as String[])
            }


            Class  aClass = writerUtil.class
            Field field = aClass.getDeclaredField("outputFile");

            field.setAccessible(true)
            if (writerUtil != null) {
                filePath = ((File)field.get(writerUtil))?.getAbsolutePath()
            } else {
                filePath = null
            }


        } catch (Exception e) {
            log.info(e.getMessage())
        } finally {
            writerUtil?.finishWriting()
            sql?.close()
        }


        return filePath
    }


    private void pivotData(boolean multipleStudies, String study, String inputFileLoc, boolean mRNAExists, boolean snpExists) {

        if (StringUtils.isNotEmpty(inputFileLoc)) {
            File inputFile = new File(inputFileLoc)
            if (null != inputFile) {
                //Set the working directory to be our temporary location.
                String workingDirectory = inputFile.getParent()

                // convert data
                if (mRNAExists) {
                    new ClinicalMRNAPivotDataConverter(
                            multipleStudies,
                            study,
                            inputFileLoc,
                            workingDirectory,
                            true,
                            snpExists
                    ).convert()
                } else {
                    new ClinicalPivotDataConverter(
                            multipleStudies,
                            study,
                            inputFileLoc,
                            workingDirectory,
                            true,
                            snpExists
                    ).convert()
                }
            }
        }

    }

    def String quoteCSV(String val) {
        String[] inArray;
        StringBuilder s = new StringBuilder();

        if (val != null && val.length() > 0) {
            inArray = val.split(",");
            s.append("'" + inArray[0] + "'");

            for (int i = 1; i < inArray.length; i++) {
                s.append(",'" + inArray[i] + "'");
            }
        }

        return s.toString();
    }

    def boolean retrievalTypeExists(String checkRetrievalType, List retrievalTypes) {

        boolean exists = false

        for (int i = 0; i < retrievalTypes.size(); i++) {
            String[] dataTypeFileType = StringUtils.split((String)retrievalTypes.get(i), ".")
            String dataType;
            if (dataTypeFileType.size() == 1) {
                dataType = retrievalTypes.get(i)
            }
            String fileType;
            if (dataTypeFileType.size() > 1) {
                dataType = dataTypeFileType[0].trim().replace(" ", "")
                fileType = dataTypeFileType[1].trim().replace(" ", "")
            }
            if (dataType == checkRetrievalType) {
                exists = true
                return exists
            }
        }

        return exists
    }


    def private getColumnNames(List retrievalTypes, Map snpFilesMap, Boolean includeParentInfo, Boolean includeConceptContext) {
        def columnNames = []
        columnNames.add("PATIENT ID")
        columnNames.add("SUBSET")
        columnNames.add("CONCEPT CODE")
        columnNames.add("CONCEPT PATH")
        columnNames.add("VALUE")
        columnNames.add("CONCEPT_PATH_FULL")
        columnNames.add("NOD")
        if (retrievalTypeExists("MRNA", retrievalTypes)) {
            columnNames.add("ASSAY ID")
        }
        if (retrievalTypeExists("SNP", retrievalTypes)) {
            if (null != snpFilesMap?.get('PEDFiles')) columnNames.add("SNP PED File")
            if (null != snpFilesMap?.get('MAPFiles')) columnNames.add("SNP MAP File")
        }

        if (includeParentInfo) {
            columnNames.add("PARENT_PATH")
            columnNames.add("PARENT_CODE")
        }

        if (includeConceptContext) {
            columnNames.add("CONTEXT_NAME")
        }

        return columnNames
    }

    def public boolean wasDataFound() {
        return dataFound
    }

    //Give a list of concept codes that represent parents to other codes, create a union statement to retrieve the children and indicate their parents.
    private String getParentConceptUnion(String[] parentConceptCodeList, Boolean includeConceptContext) {
        def queryToReturn = new StringBuilder();


        queryToReturn <<= ' UNION SELECT    ofa.PATIENT_NUM, '
        queryToReturn <<= '         C1.CONCEPT_PATH, '
        queryToReturn <<= '			C1.CONCEPT_CD, '
        queryToReturn <<= '			C1.NAME_CHAR, '
        queryToReturn <<= '			CASE ofa.VALTYPE_CD '
        queryToReturn <<= '				WHEN \'T\' THEN TVAL_CHAR '
        queryToReturn <<= '				WHEN \'N\' THEN CAST(NVAL_NUM AS VARCHAR(30)) '
        queryToReturn <<= '			END as VALUE, ? SUBSET , pd.sourcesystem_cd, '
        queryToReturn <<= '			C2.CONCEPT_PATH AS PARENT_PATH, '
        queryToReturn <<= '			C2.CONCEPT_CD AS PARENT_CODE '

        //If we are including the concepts context, add the columns to the statement here.
        if (includeConceptContext) {
            queryToReturn <<= ', DC.DE_CONTEXT_NAME '
        }

        queryToReturn <<= 'FROM	qt_patient_set_collection qt '
        queryToReturn <<= 'INNER JOIN OBSERVATION_FACT ofa ON qt.PATIENT_NUM = ofa.PATIENT_NUM  '

        //If we are including the concepts context, add the columns to the statement here.
        if (includeConceptContext) {
            queryToReturn <<= ' LEFT JOIN DEAPP.DE_CONCEPT_CONTEXT DCC ON DCC.CONCEPT_CD = ofa.CONCEPT_CD '
            queryToReturn <<= ' LEFT JOIN DEAPP.DE_CONTEXT DC ON DC.DE_CONTEXT_ID = DCC.DE_CONTEXT_ID '
        }


        queryToReturn <<= 'INNER JOIN PATIENT_DIMENSION pd on ofa.patient_num = pd.patient_num '
        queryToReturn <<= 'INNER JOIN DE_XTRIAL_CHILD_MAP XMAP ON XMAP.CONCEPT_CD = ofa.CONCEPT_CD '
        queryToReturn <<= 'INNER JOIN CONCEPT_DIMENSION C1 ON C1.CONCEPT_CD = XMAP.CONCEPT_CD '
        queryToReturn <<= 'INNER JOIN CONCEPT_DIMENSION C2 ON C2.CONCEPT_CD = XMAP.PARENT_CD '
        queryToReturn <<= 'WHERE	qt.RESULT_INSTANCE_ID = ? '
        queryToReturn <<= 'AND		ofa.CONCEPT_CD IN '
        queryToReturn <<= '( '
        queryToReturn <<= '		SELECT	C_BASECODE '
        queryToReturn <<= '		FROM	I2B2 '
        queryToReturn <<= '		WHERE	C_FULLNAME LIKE ( '
        queryToReturn <<= '					SELECT	CONCEPT_PATH || \'%\' '
        queryToReturn <<= '					FROM	CONCEPT_DIMENSION '
        queryToReturn <<= '					WHERE	CONCEPT_CD = ?) '
        queryToReturn <<= '     AND C_VISUALATTRIBUTES != \'FA\' '
        queryToReturn <<= ') '

    }


    def newExportData() {

    }
}