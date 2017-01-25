package com.recomdata.transmart.data.export

import au.com.bytecode.opencsv.CSVWriter
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import org.transmartproject.core.concept.ConceptKey
import org.transmartproject.core.dataquery.TabularResult
import org.transmartproject.core.dataquery.clinical.ClinicalVariableColumn
import org.transmartproject.core.dataquery.clinical.ComposedVariable
import org.transmartproject.core.dataquery.clinical.PatientRow
import org.transmartproject.core.ontology.OntologyTerm
import org.transmartproject.core.ontology.OntologyTermTag
import org.transmartproject.core.ontology.Study
import org.transmartproject.core.querytool.QueryResult

import static org.transmartproject.core.dataquery.clinical.ClinicalVariable.NORMALIZED_LEAFS_VARIABLE

class ClinicalExportService {

    def queriesResourceService
    def clinicalDataResourceService
    def studiesResourceService
    def ontologyTermTagsResourceService
    def conceptsResourceService

    final static String DATA_FILE_NAME = 'data_clinical.tsv'
    final static String META_FILE_NAME = 'meta.tsv'
    final static String SUBJ_ID_TITLE = 'Subject ID'
    final static char COLUMN_SEPARATOR = '\t' as char
    final static List<String> META_FILE_HEADER = ['Variable', 'Attribute', 'Description']

    def jobResultsService

    List<File> exportClinicalData(Map args) {
        String jobName = args.jobName
        if (jobResultsService.isJobCancelled(jobName)) {
            return null
        }

        Long resultInstanceId = args.resultInstanceId as Long
        List<String> conceptKeys = args.conceptKeys
        File studyDir = args.studyDir
        boolean exportMetaData = args.exportMetaData == null ? true : args.exportMetaData

        QueryResult queryResult = queriesResourceService.getQueryResultFromId(resultInstanceId)
        List<List<ComposedVariable>> variables
        if (conceptKeys) {
            variables = createClinicalVariablesForConceptKeys(conceptKeys)
        } else {
            Set<Study> studies = getQueriedStudies(queryResult)
            variables = createClinicalVariablesForStudies(studies)
        }

        def files = []
        for (int i=0; i<variables.size(); i++){
            files << exportClinicalDataToFile(queryResult, variables.get(i), studyDir, jobName, "data_clinical" + i + ".tsv")
        }
        if (exportMetaData) {
            def terms = getRelatedOntologyTerms(variables)
            def tagsFile = exportAllTags(terms, studyDir)
            if (tagsFile) {
                files << tagsFile
            }
        }

        files
    }

    private File exportClinicalDataToFile(QueryResult queryResult,
                                          List<ComposedVariable> variables,
                                          File studyDir,
                                          String jobName,
                                          String outputFileName) {

        TabularResult<ClinicalVariableColumn, PatientRow> tabularResult =
                clinicalDataResourceService.retrieveData(queryResult, variables)

        try {
            return writeToFile(tabularResult, variables, studyDir, jobName, outputFileName)
        } finally {
            tabularResult.close()
        }
    }

    private File writeToFile(TabularResult<ClinicalVariableColumn, PatientRow> tabularResult,
                             List<ComposedVariable> variables,
                             File studyDir,
                             String jobName,
                             String outputFileName) {
        PeekingIterator peekingIterator = Iterators.peekingIterator(tabularResult.iterator())

        File clinicalDataFile = new File(studyDir, outputFileName)
        clinicalDataFile.withWriter { Writer writer ->
            CSVWriter csvWriter = new CSVWriter(writer, COLUMN_SEPARATOR)

            def firstRow = peekingIterator.peek()
            List headRowList = [SUBJ_ID_TITLE] +
                    variables.collectMany { ComposedVariable var ->
                        firstRow[var].collect { it.key.label }
                    }

            csvWriter.writeNext(headRowList as String[])

            while (peekingIterator.hasNext()) {
                if (jobResultsService.isJobCancelled(jobName)) {
                    return null
                }
                def row = peekingIterator.next()
                List rowList = [row.patient.inTrialId] +
                        variables.collectMany { ComposedVariable var ->
                            row[var].values()
                        }

                csvWriter.writeNext(rowList as String[])
            }
        }

        clinicalDataFile
    }

    File exportAllTags(Set<OntologyTerm> terms, File studyDir) {
        def tagsMap = ontologyTermTagsResourceService.getTags(terms, true)

        if (tagsMap) {
            def resultFile = new File(studyDir, META_FILE_NAME)

            resultFile.withWriter { Writer writer ->
                CSVWriter csvWriter = new CSVWriter(writer, COLUMN_SEPARATOR)
                csvWriter.writeNext(META_FILE_HEADER as String[])
                tagsMap.each { OntologyTerm keyTerm, List<OntologyTermTag> valueTags ->
                    valueTags.each { OntologyTermTag tag ->
                        csvWriter.writeNext([keyTerm.fullName, tag.name, tag.description] as String[])
                    }
                }
            }

            resultFile
        }
    }

    private Set<OntologyTerm> getRelatedOntologyTerms(List<ComposedVariable> variables) {
        variables.collect { ComposedVariable variable ->
            conceptsResourceService.getByKey(variable.key.toString())
        } as Set
    }

    /*private Collection<ComposedVariable> createClinicalVariablesForConceptKeys(Collection<String> conceptKeys) {
        conceptKeys.collectAll {
            def conceptKey = new ConceptKey(it)
            clinicalDataResourceService.createClinicalVariable(
                    NORMALIZED_LEAFS_VARIABLE,
                    concept_path: conceptKey.conceptFullName.toString())
        }
    }*/

    private List<Collection<String>> splitOverSizedConceptKeyCollection(Collection<String> overSizedConceptKeyCollection){
        List<Collection<String>> result = new ArrayList<Collection<String>>();
        if (overSizedConceptKeyCollection.size() < 65535){
            System.out.println ("Undersized ConceptKeyCollection with size " + overSizedConceptKeyCollection.size());
            result.add(overSizedComVarCollection);
            return result;
        }
        else {
            System.out.println ("Oversized ConceptKeyCollection with size " + overSizedConceptKeyCollection.size());
            Collection<String> partialConceptKeyCollection = new ArrayList<String>();
            Collection<String> restOfConceptKeyCollection = new ArrayList<String>();
            for (int i=0; i<overSizedConceptKeyCollection; i++){
                if (i < 65535){
                    partialConceptKeyCollection.add(overSizedConceptKeyCollection.get(i));
                }
                else {
                    restOfConceptKeyCollection.add(overSizedConceptKeyCollection.get(i));
                }
            }
            result.add(partialConceptKeyCollection);
            return result.addAll(splitOverSizedConceptKeyCollection(restOfConceptKeyCollection));
        }
    }

    private List<Set<Study>> splitOverSizedStudySet(Set<Study> overSizedStudySet){
        List<Set<Study>> result = new ArrayList<Set<Study>>();
        
        if (overSizedStudySet.size() < 65535){
            System.out.println("Undersized StudySet with size " + overSizedStudySet.size());
            result.add(overSizedStudySet);
            return result;
        }
        else {
            System.out.println("Oversized StudySet with size " + overSizedStudySet.size());
            Set<Study> partialStudySet = new HashSet<Study>();
            Set<Study> restOfStudySet = new HashSet<Study>();
            Iterator<Study> overSizedStudySetIterator = overSizedStudySet.iterator();
            int currentOverSizedStudySetIndex = 0;
            while (overSizedStudySetIterator.hasNext()){
                Study currentStudy = overSizedStudySetIterator.next();
                if (currentOverSizedStudySetIndex < 65535){
                    partialStudySet.add(currentStudy);
                }
                else {
                    restOfStudySet.add(currentStudy);
                }
                currentOverSizedStudySetIndex++;
            }
            result.add(partialConceptKeyCollection);
            return result.addAll(splitOverSizedStudySet(restOfStudySet));
        }

    }

    private List<Collection<ComposedVariable>> createClinicalVariablesForConceptKeys(Collection<String> conceptKeys) {
        List<Collection<String>> splitConceptKeys = splitOverSizedConceptKeyCollection(conceptKeys);
        System.out.println("The conceptKeys were broken up into " + splitConceptKeys.size() + "parts");
        List<Collection<ComposedVariable>> result = new ArrayList<Collection<ComposedVariable>>();
        for (int i=0; i < splitConceptKeys.size(); i++){
            Collection<ComposedVariable> composedVariableCollection = conceptKeys.collectAll {
                def conceptKey = new ConceptKey(it)
                clinicalDataResourceService.createClinicalVariable(
                    NORMALIZED_LEAFS_VARIABLE,
                    concept_path: conceptKey.conceptFullName.toString())
            }
            result.add(composedVariableCollection);
        }
        return result;
    }

    private List<Collection<ComposedVariable>> createClinicalVariablesForStudies(Set<Study> queriedStudies) {
        List<Set<Study>> splitStudies = splitOverSizedStudySet(queriedStudies);
        System.out.println("The studies were broken up into " + splitStudies.size() + "parts");
        List<Collection<ComposedVariable>> result = new ArrayList<Collection<ComposedVariable>>();
        for (int i=0; i < splitStudies.size(); i++){
            Collection<ComposedVariable> composedVariableCollection = queriedStudies.collect { Study study ->
                clinicalDataResourceService.createClinicalVariable(
                    NORMALIZED_LEAFS_VARIABLE,
                    concept_path: study.ontologyTerm.fullName)
            }
            result.add(composedVariableCollection);
        }
        return result;
    }

    private Set<Study> getQueriedStudies(QueryResult queryResult) {
        def trials = queryResult.patients*.trial as Set

        trials.collect { String trialId ->
            studiesResourceService.getStudyById(trialId)
        } as Set
    }

}
