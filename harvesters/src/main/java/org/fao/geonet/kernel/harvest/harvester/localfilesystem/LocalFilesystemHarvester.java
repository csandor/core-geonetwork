//=============================================================================
//===	Copyright (C) 2001-2009 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================
package org.fao.geonet.kernel.harvest.harvester.localfilesystem;

import jeeves.server.context.ServiceContext;
import org.fao.geonet.Logger;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.OperationAllowedId_;
import org.fao.geonet.domain.Source;
import org.fao.geonet.exceptions.BadInputEx;
import org.fao.geonet.kernel.harvest.BaseAligner;
import org.fao.geonet.kernel.harvest.harvester.AbstractHarvester;
import org.fao.geonet.kernel.harvest.harvester.AbstractParams;
import org.fao.geonet.kernel.harvest.harvester.CategoryMapper;
import org.fao.geonet.kernel.harvest.harvester.GroupMapper;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.SourceRepository;
import org.fao.geonet.repository.Updater;
import org.fao.geonet.resources.Resources;
import org.fao.geonet.utils.IO;
import org.jdom.Element;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Harvester for local filesystem.
 * 
 * @author heikki doeleman
 *
 */
public class LocalFilesystemHarvester extends AbstractHarvester<HarvestResult> {
	
	//FIXME Put on a different file?
	private BaseAligner aligner = new BaseAligner() {};
	private LocalFilesystemParams params;

	
	@Override
	protected void storeNodeExtra(AbstractParams params, String path, String siteId, String optionsId) throws SQLException {
		LocalFilesystemParams lp = (LocalFilesystemParams) params;
        super.setParams(lp);
        
        settingMan.add( "id:"+siteId, "icon", lp.icon);
		settingMan.add( "id:"+siteId, "recurse", lp.recurse);
		settingMan.add( "id:"+siteId, "directory", lp.directoryname);
		settingMan.add( "id:"+siteId, "nodelete", lp.nodelete);
        settingMan.add( "id:"+siteId, "checkFileLastModifiedForUpdate", lp.checkFileLastModifiedForUpdate);
	}

	@Override
	protected String doAdd(Element node) throws BadInputEx, SQLException {
		params = new LocalFilesystemParams(dataMan);
        super.setParams(params);

        //--- retrieve/initialize information
		params.create(node);
		
		//--- force the creation of a new uuid
		params.uuid = UUID.randomUUID().toString();
		
		String id = settingMan.add( "harvesting", "node", getType());
		storeNode( params, "id:"+id);

        Source source = new Source(params.uuid, params.name, true);
        context.getBean(SourceRepository.class).save(source);
        Resources.copyLogo(context, "images" + File.separator + "harvesting" + File.separator + params.icon, params.uuid);
        	
		return id;
	}

    /**
     * Aligns new results from filesystem harvesting. Contrary to practice in e.g. CSW Harvesting,
     * files removed from the harvesting source are NOT removed from the database. Also, no checks
     * on modification date are done; the result gets inserted or replaced if the result appears to
     * be in a supported schema.
     *
     * @param root the directory to visit
     * @throws Exception
     */
    private HarvestResult align(Path root) throws Exception {
        log.debug("Start of alignment for : " + params.name);
        final LocalFsHarvesterFileVisitor visitor = new LocalFsHarvesterFileVisitor(context, params, log, this);
        if (params.recurse) {
            Files.walkFileTree(root, visitor);
        } else {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(root)) {
                for (Path path : paths) {
                    if (Files.isRegularFile(path)) {
                        visitor.visitFile(path, Files.readAttributes(path, BasicFileAttributes.class));
                    }
                }
            }
        }
        result = visitor.getResult();
        List<String> idsForHarvestingResult = visitor.getIdsForHarvestingResult();
        if (!params.nodelete) {
            //
            // delete locally existing metadata from the same source if they were
            // not in this harvesting result
            //
            List<Metadata> existingMetadata = context.getBean(MetadataRepository.class).findAllByHarvestInfo_Uuid(params.uuid);
            for (Metadata existingId : existingMetadata) {
                String ex$ = String.valueOf(existingId.getId());
                if (!idsForHarvestingResult.contains(ex$)) {
                    log.debug("  Removing: " + ex$);
                    dataMan.deleteMetadata(context, ex$);
                    result.locallyRemoved++;
                }
            }
        }
        log.debug("End of alignment for : " + params.name);
        return result;
    }

	void updateMetadata(Element xml, final String id, GroupMapper localGroups,
                        final CategoryMapper localCateg, String changeDate) throws Exception {
		log.debug("  - Updating metadata with id: "+ id);

        //
        // update metadata
        //
        boolean validate = false;
        boolean ufo = false;
        boolean index = false;
        String language = context.getLanguage();

        final Metadata metadata = dataMan.updateMetadata(context, id, xml, validate, ufo, index, language, changeDate,
                true);

        OperationAllowedRepository repository = context.getBean(OperationAllowedRepository.class);
        repository.deleteAllByIdAttribute(OperationAllowedId_.metadataId, Integer.parseInt(id));
        aligner.addPrivileges(id, params.getPrivileges(), localGroups, dataMan, context, log);

        metadata.getCategories().clear();
        aligner.addCategories(metadata, params.getCategories(), localCateg, context, log, null);

        dataMan.flush();

        dataMan.indexMetadata(id, false);
	}

	
	/**
	 * Inserts a metadata into the database. Lucene index is updated after insertion.
	 * @param xml
	 * @param uuid
	 * @param schema
	 * @param localGroups
	 * @param localCateg
	 * @param createDate TODO
	 * @throws Exception
	 */
    String addMetadata(Element xml, String uuid, String schema, GroupMapper localGroups, final CategoryMapper localCateg, String createDate) throws Exception {
		log.debug("  - Adding metadata with remote uuid: "+ uuid);

		String source = params.uuid;
		
        //
        // insert metadata
        //
        String group = null, isTemplate = null, docType = null, title = null, category = null;
        boolean ufo = false, indexImmediate = false;
        String id = dataMan.insertMetadata(context, schema, xml, uuid, Integer.parseInt(params.ownerId), group, source,
                         isTemplate, docType, category, createDate, createDate, ufo, indexImmediate);

		int iId = Integer.parseInt(id);
		dataMan.setTemplateExt(iId, MetadataType.METADATA);
		dataMan.setHarvestedExt(iId, source);

        aligner.addPrivileges(id, params.getPrivileges(), localGroups, dataMan, context, log);
        context.getBean(MetadataRepository.class).update(iId, new Updater<Metadata>() {
            @Override
            public void apply(@Nonnull Metadata entity) {
                aligner.addCategories(entity, params.getCategories(), localCateg, context, log, null);
            }
        });

        dataMan.flush();

        dataMan.indexMetadata(id, false);
		return id;
    }

	@Override
    public void doHarvest(Logger l) throws Exception {
		log.debug("LocalFilesystem doHarvest: top directory is " + params.directoryname + ", recurse is " + params.recurse);
		Path directory = IO.toPath(params.directoryname);
		this.result = align(directory);
	}

	@Override
	protected void doInit(Element entry, ServiceContext context) throws BadInputEx {
		params = new LocalFilesystemParams(dataMan);
        super.setParams(params);
        params.create(entry);
	}

	@Override
	protected void doUpdate(String id, Element node) throws BadInputEx, SQLException {
		LocalFilesystemParams copy = params.copy();

		//--- update variables
		copy.update(node);

		String path = "harvesting/id:"+ id;

		settingMan.removeChildren(path);

		//--- update database
		storeNode(copy, path);

		//--- we update a copy first because if there is an exception LocalFilesystemParams
		//--- could be half updated and so it could be in an inconsistent state

        Source source = new Source(copy.uuid, copy.name, true);
        context.getBean(SourceRepository.class).save(source);
        Resources.copyLogo(context, "images" + File.separator + "harvesting" + File.separator + copy.icon, copy.uuid);
		
		params = copy;
        super.setParams(params);

    }

}