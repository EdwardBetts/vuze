/*
 * Created on May 6, 2008
 * Created by Paul Gardner
 * 
 * Copyright 2008 Vuze, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License only.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package com.aelitis.azureus.core.metasearch.impl;

import java.io.IOException;
import java.util.*;

import org.gudy.azureus2.core3.util.AERunnable;
import org.gudy.azureus2.core3.util.AsyncDispatcher;
import org.gudy.azureus2.core3.util.Debug;
import org.gudy.azureus2.core3.util.DelayedEvent;
import org.gudy.azureus2.core3.util.FileUtil;
import org.gudy.azureus2.plugins.utils.search.SearchProvider;

import com.aelitis.azureus.core.messenger.config.PlatformMetaSearchMessenger;
import com.aelitis.azureus.core.metasearch.*;
import com.aelitis.azureus.core.metasearch.impl.plugin.PluginEngine;
import com.aelitis.azureus.core.util.CopyOnWriteList;

public class 
MetaSearchImpl
	implements MetaSearch
{
	private static final String	CONFIG_FILE = "metasearch.config";
		
	private MetaSearchManagerImpl	manager;
	
	private CopyOnWriteList 	engines 	= new CopyOnWriteList();
	private Map					plugin_map	= new HashMap();
	
	private boolean config_dirty;
	
	protected 
	MetaSearchImpl(
		MetaSearchManagerImpl		_manager )
	{
		manager	= _manager;
		
		loadConfig();
	}
	
	public EngineImpl
	importFromBEncodedMap(
		Map		map )
	
		throws IOException
	{
		return( EngineImpl.importFromBEncodedMap( this, map )); 
	}
	
	public Engine
	importFromJSONString(
		int			type,
		long		id,
		long		last_updated,
		String		name,
		String		content )
	
		throws IOException
	{
		return( EngineImpl.importFromJSONString( this, type, id, last_updated, name, content ));
	}
	
	public EngineImpl
	importFromPlugin(
		String				pid,
		SearchProvider		provider )
	
		throws IOException
	{
		synchronized( this ){

			Long	l_id = (Long)plugin_map.get( pid );
			
			long	id;
			
			if ( l_id == null ){
				
				Random random = new Random();
				
				while( true ){
				
					id = (long)Integer.MAX_VALUE + (long)Math.abs(random.nextInt());
					
					if ( getEngine( id ) == null ){
						
						plugin_map.put( pid, new Long( id ));
						
						configDirty();
						
						break;
					}
				}
			}else{
				
				id = l_id.longValue();
			}
			
			EngineImpl engine = (EngineImpl)getEngine( id );
			
			if ( engine == null ){
				
				engine = new PluginEngine( this, id, provider );
				
				engine.setSource( Engine.ENGINE_SOURCE_LOCAL );
				
				engine.setSelectionState( Engine.SEL_STATE_MANUAL_SELECTED );
				
				addEngine( engine );
				
			}else{
				
				if ( engine instanceof PluginEngine ){
					
					((PluginEngine)engine).setProvider( provider );
					
				}else{
					
					Debug.out( "Inconsistent: plugin must be a PluginEngine!" );
					
					plugin_map.remove( pid );
					
					removeEngine( engine );
					
					throw( new IOException( "Inconsistent" ));
				}
			}
			
			return( engine );
		}	
	}
	
	public void 
	addEngine(
		Engine 	engine )
	{
		addEngine( engine, false );
	}
	
	public void 
	addEngine(
		Engine 	engine,
		boolean	loading )
	{
		synchronized( this ){
			
			Iterator	it = engines.iterator();
			
			while( it.hasNext()){
				
				Engine e = (Engine)it.next();
				
				if ( e.getId() == engine.getId()){
					
					log( "Removing old engine with same ID: " + e.getName());
					
					it.remove();
				}
			}
			
			engines.add( engine );
		}
		
		if ( !loading ){
			
			log( "Engine '" + engine.getName() + "' added" );
			
			saveConfig();
		}
	}
	
	public Engine 
	addEngine(
		long 		id )
	
		throws MetaSearchException 
	{
		try{

			PlatformMetaSearchMessenger.templateDetails details = PlatformMetaSearchMessenger.getTemplate( id );
		
			log( "Downloading definition of template " + id );
			log( details.getValue());
		
			if ( details.isVisible()){
			
				Engine engine = 
					importFromJSONString( 
						details.getType()==PlatformMetaSearchMessenger.templateDetails.ENGINE_TYPE_JSON?Engine.ENGINE_TYPE_JSON:Engine.ENGINE_TYPE_REGEX,
						details.getId(),
						details.getModifiedDate(),
						details.getName(),
						details.getValue());
				
				engine.setSource( Engine.ENGINE_SOURCE_VUZE );
				engine.setSelectionState( Engine.SEL_STATE_DESELECTED );
				
				addEngine( engine );
				
				return( engine );
				
			}else{
				
				throw( new MetaSearchException( "Search template is not visible" ));
			}
		}catch( MetaSearchException e ){
			
			throw( e );
			
		}catch( Throwable e ){
		
			throw( new MetaSearchException( "Template load failed", e ));
		}	
	}
			
	public void 
	removeEngine(
		Engine 	engine )
	{
		if ( engines.remove( engine )){
		
			log( "Engine '" + engine.getName() + "' removed" );
			
			saveConfig();
		}
	}
	
	public Engine[] 
	getEngines(
		boolean		active_only,
		boolean		ensure_up_to_date )
	{
		if ( ensure_up_to_date ){
			
			manager.ensureEnginesUpToDate();
		}
		
		List l = engines.getList();
				
		List result;
		
		if ( active_only ){
			
			result = new ArrayList();
			
			for (int i=0;i<l.size();i++){
				
				Engine	e = (Engine)l.get(i);
				
				if ( e.isActive()){
					
					result.add( e );
				}
			}
		}else{
			
			result = l;
		}
		
		return( (Engine[])result.toArray( new Engine[ result.size() ]));
	}
	
	public Engine
	getEngine(
		long		id )
	{
		List l = engines.getList();
		
		for( int i=0;i<l.size();i++){
			
			Engine e = (Engine)l.get(i);
			
			if ( e.getId() == id ){
				
				return( e );
			}
		}

		return( null );
	}
	
	public void 
	search(
		final ResultListener 	original_listener,
		SearchParameter[] 		searchParameters,
		String					headers )
	{
		String	param_str = "";
		
		for (int i=0;i<searchParameters.length;i++){
		
			SearchParameter param = searchParameters[i];
			
			param_str += (i==0?"":",") + param.getMatchPattern() + "->" + param.getValue();
		}
		
		ResultListener	listener = 
			new ResultListener()
			{
					// 	single thread listener calls
			
				private AsyncDispatcher dispatcher = new AsyncDispatcher( 5000 );

				public void 
				contentReceived(
					final Engine engine, 
					final String content ) 
				{
					dispatcher.dispatch(
							new AERunnable()
							{
								public void
								runSupport()
								{
									original_listener.contentReceived( engine, content );
								}
							});
				}
				
				public void 
				matchFound(
					final Engine 	engine, 
					final String[] 	fields )
				{
					dispatcher.dispatch(
							new AERunnable()
							{
								public void
								runSupport()
								{
									original_listener.matchFound( engine, fields );
								}
							});	
				}
				
				public void 
				resultsReceived(
					final Engine 	engine,
					final Result[] 	results )
				{
					dispatcher.dispatch(
						new AERunnable()
						{
							public void
							runSupport()
							{
								final int	CHUNK_SIZE 	= 25;
								final int	CHUNK_DELAY	= 500;
								
								for (int i=0;i<results.length;i+=CHUNK_SIZE){
									
									int	to_do = Math.min( CHUNK_SIZE, results.length - i );
									
									Result[] chunk = new Result[to_do];
									
									System.out.println( "sending " + i + " to " + ( i+to_do ) + " of " + results.length );
									
									System.arraycopy(results, i, chunk, 0, to_do );
									
									original_listener.resultsReceived( engine, chunk );
									
									if ( results.length - i > CHUNK_SIZE ){
										
										try{
											Thread.sleep(CHUNK_DELAY);
											
										}catch( Throwable e ){
											
										}
									}
								}
							}
						});
				}
			
				public void 
				resultsComplete(
					final Engine engine )
				{
					dispatcher.dispatch(
							new AERunnable()
							{
								public void
								runSupport()
								{
									original_listener.resultsComplete( engine );
								}
							});
				}
			
				public void 
				engineFailed(
					final Engine 	engine,
					final Throwable	e )
				{
					dispatcher.dispatch(
							new AERunnable()
							{
								public void
								runSupport()
								{
									original_listener.engineFailed( engine, e );
								}
							});
				}
			};
			
		SearchExecuter se = new SearchExecuter(listener);
		
		Engine[] engines = getEngines( true, true );

		String	engines_str = "";
		
		for (int i=0;i<engines.length;i++){
			
			engines_str += (i==0?"":",") + engines[i].getId();
		}
		
		log( "Search: params=" + param_str + "; engines=" + engines_str );
		

		for (int i=0;i<engines.length;i++){
			
			se.search( engines[i], searchParameters, headers );
		}
	}
	
	protected void
	loadConfig()
	{
		log( "Loading configuration" );
		
		synchronized( this ){
			
			Map map = FileUtil.readResilientConfigFile( CONFIG_FILE );
			
			List	l_engines = (List)map.get( "engines" );
			
			if( l_engines != null ){
				
				for (int i=0;i<l_engines.size();i++){
					
					Map	m = (Map)l_engines.get(i);
					
					try{
						Engine e = importFromBEncodedMap( m );
						
						addEngine( e, true );
						
						log( "    loaded " + e.getString());
						
					}catch( Throwable e ){
						
						log( "Failed to import engine from " + m, e );
					}
				}
			}
			
			Map	p_map = (Map)map.get( "plugin_map" );
			
			if ( p_map != null ){
				
				plugin_map = p_map;
			}
		}
	}
	
	protected void
	configDirty()
	{
		synchronized( this ){
			
			if ( config_dirty ){
				
				return;
			}
			
			config_dirty = true;
		
			new DelayedEvent( 
				"MetaSearch:save", 5000,
				new AERunnable()
				{
					public void 
					runSupport() 
					{
						synchronized( this ){
							
							if ( !config_dirty ){

								return;
							}
							
							saveConfig();
						}	
					}
				});
		}
	}
	
	protected void
	saveConfig()
	{
		log( "Saving configuration" );
		
		synchronized( this ){
			
			config_dirty = false;
			
			Map map = new HashMap();
			
			List	l_engines = new ArrayList();
			
			map.put( "engines", l_engines );
			
			Iterator	it = engines.iterator();
			
			while( it.hasNext()){
				
				Engine e = (Engine)it.next();
			
				try{
					
					l_engines.add( e.exportToBencodedMap());
					
				}catch( Throwable f ){
					
					log( "Failed to export engine " + e.getName(), f );
				}
			}
			
			if ( plugin_map != null ){
				
				map.put( "plugin_map", plugin_map );
			}
			
			FileUtil.writeResilientConfigFile( CONFIG_FILE, map );
		}
	}
	
	protected void
	log(
		String	str )
	{
		manager.log( "search :"  + str );
	}
	
	protected void
	log(
		String		str,
		Throwable 	e )
	{
		manager.log( "search :"  +  str, e );
	}
}
