/*************************************************************************
 * tranSMART - translational medicine data mart
 * 
 * Copyright 2008-2012 Janssen Research & Development, LLC.
 * 
 * This product includes software developed at Janssen Research & Development, LLC.
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License 
 * as published by the Free Software  * Foundation, either version 3 of the License, or (at your option) any later version, along with the following terms:
 * 1.	You may convey a work based on this program in accordance with section 5, provided that you retain the above notices.
 * 2.	You may convey verbatim copies of this program code as you receive it, in any medium, provided that you retain the above notices.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS    * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *
 ******************************************************************/
  

package transmartapp

import grails.converters.*
import org.json.*;

import auth.AuthUser;

class OntologyService {

    boolean transactional = true
	
	def i2b2HelperService
	def springSecurityService

    def searchOntology(searchtags, searchterms, tagsearchtype, returnType, accessionsToInclude) {

			def concepts=[];
    		def myNodes;
			
			if(searchterms?.size() ==0) {
				searchterm = null;
			}
    		log.trace("searching for:"+searchtags+" of type"+tagsearchtype+"with searchterms:"+searchterms.join(","))
			def myCount  =0;
			def allSystemCds = []
			def visualAttrHiddenWild = '%H%';
			
			if(searchterms==null){// if there is no search term just do exact match
    		def c = i2b2.OntNode.createCriteria()
    		 myCount = c.get{
    		    projections{
    				countDistinct("id")
    				and
    			{
    				if(searchtags!=null)
    				{
    					tags {
    						and {
    							eq('tagtype', tagsearchtype)
    							'in'("tag", searchtags)	
    							}
    						}
    				}
					not {ilike('visualattributes', '%H%')} //h for hidden
    			}
    		    }
    		}
    		log.trace("SEARCH COUNT:"+myCount);
			
			def d=i2b2.OntNode.createCriteria();
			myNodes = d.list {
				and
				{
					if(searchtags!=null)
					{
						tags {
							and {
								//like('tag', '%'+tagsearchterm+'%')
								eq('tagtype', tagsearchtype)
								'in'("tag", searchtags)
								}
							}
					}
					if(searchterm!=null)
					{
						ilike('name', '%'+searchterm+'%')
					}
					not {ilike('visualattributes', '%H%')} //h for hidden
				}
				maxResults(100)
			}
			}else {
			// if there is a search term then use tag type to find system cd
			// this is not a generic solution - 
			// if tag type is all then do a name like search
			
			def searchtermstring = ""
			for (searchterm in searchterms) {
				searchterm = searchterm?.trim();
				if (searchterm) {
					if (!searchtermstring.equals("")) {
						searchtermstring += " OR "
					}
					def searchtermWild = '%'+searchterm.toLowerCase()+'%';
					searchtermstring += "lower(o.name) like '"+searchtermWild+"' "
				}
			}
			
			if(tagsearchtype=='ALL'){
				def countQuery = "SELECT COUNT(DISTINCT o.id) from i2b2.OntNode o WHERE (_searchterms_) AND o.visualattributes NOT like '"+visualAttrHiddenWild+"'"
				def nodeQuery = "SELECT o from i2b2.OntNode o WHERE (_searchterms_) AND o.visualattributes NOT like '"+visualAttrHiddenWild+"'"
				
				countQuery = countQuery.replace("_searchterms_", searchtermstring)
				nodeQuery = nodeQuery.replace("_searchterms_", searchtermstring)
				
				myCount = i2b2.OntNode.executeQuery(countQuery)[0]
				myNodes = i2b2.OntNode.executeQuery(nodeQuery, [max:100])
  
			}else{
			
				def cdQuery = "SELECT DISTINCT o.sourcesystemcd FROM i2b2.OntNode o JOIN o.tags t WHERE t.tag IN (:tagArg) AND t.tagtype =:tagTypeArg"
				allSystemCds = i2b2.OntNode.executeQuery(cdQuery,[tagArg:searchtags, tagTypeArg:tagsearchtype], [max:800])
			 	
				def countQuery = "SELECT COUNT(DISTINCT o.id) from i2b2.OntNode o WHERE o.sourcesystemcd IN (:scdArg) AND (_searchterms_) AND o.visualattributes NOT like '"+visualAttrHiddenWild+"'"
				countQuery = countQuery.replace("_searchterms_", searchtermstring)
				myCount = i2b2.OntNode.executeQuery(countQuery, [scdArg:allSystemCds])[0]
			  
				def nodeQuery = "SELECT o from i2b2.OntNode o WHERE o.sourcesystemcd IN (:scdArg) AND (_searchterms_) AND o.visualattributes NOT like '"+visualAttrHiddenWild+"'"
				nodeQuery = nodeQuery.replace("_searchterms_", searchtermstring)
				myNodes = i2b2.OntNode.executeQuery(nodeQuery, [scdArg:allSystemCds], [max:100])
			}
			 }
			
    	
    		//check the security
    		def keys=[:]
    		myNodes.each{node ->
    		//keys.add("\\"+node.id.substring(0,node.id.indexOf("\\",2))+node.id)
    		keys.put(node.id, node.securitytoken)
    		log.trace(node.id+" security token:"+node.securitytoken)
    		}
    		def user = AuthUser.findByUsername(springSecurityService.getPrincipal().username)
    		def access=i2b2HelperService.getAccess(keys, user);
    		log.trace(access as JSON)
    		
			if (returnType.equals("JSON")) {
	    		//build the JSON for the client
	    		myNodes.each{node -> 
	    		log.trace(node.id)
				 def level=node.hlevel
				 def key="\\"+node.id.substring(0,node.id.indexOf("\\",2))+node.id
				 def name=node.name
				 def synonym_cd=node.synonymcd
				 def visualattributes=node.visualattributes
				 def totalnum=node.totalnum
				 def facttablecolumn=node.facttablecolumn
				 def tablename=node.tablename
				 def columnname=node.columnname
				 def columndatatype=node.columndatatype
				 def operator=node.operator
				 def dimcode=node.dimcode
				 def comment=node.comment
				 def tooltip=node.tooltip
				 def metadataxml=i2b2HelperService.metadataxmlToJSON(node.metadataxml)
				 concepts.add([level:level, key:key,  name:name, synonym_cd:synonym_cd, visualattributes:visualattributes, totalnum:totalnum, facttablecolumn:facttablecolumn, tablename:tablename, columnname:columnname, columndatatype:columndatatype, operator:operator, dimcode:dimcode, comment:comment, tooltip:tooltip, metadataxml:metadataxml, access:access[node.id]] )
	    					
	    		}
	            def resulttext;
	            if(myCount<100){resulttext="Found "+myCount+" results."}
	            else
	            {resulttext ="Returned first 100 of "+myCount+" results."}
	            
	    		def result=[concepts:concepts, resulttext:resulttext]
	    		log.trace(result as JSON)
				
				return result
			}
			else if (returnType.equals("accession")) {
				def accessions = []
				myNodes.each{node ->
					if (!accessions.contains(node.sourcesystemcd)) {
						accessions.add(node.sourcesystemcd)
					}
				}
				return accessions
			}
	}
}
