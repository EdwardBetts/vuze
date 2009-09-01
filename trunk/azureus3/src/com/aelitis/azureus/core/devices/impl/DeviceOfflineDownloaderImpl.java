/*
 * Created on Jan 28, 2009
 * Created by Paul Gardner
 * 
 * Copyright 2009 Vuze, Inc.  All rights reserved.
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


package com.aelitis.azureus.core.devices.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;

import org.gudy.azureus2.core3.disk.DiskManager;
import org.gudy.azureus2.core3.disk.DiskManagerPiece;
import org.gudy.azureus2.core3.download.DownloadManager;
import org.gudy.azureus2.core3.global.GlobalManager;
import org.gudy.azureus2.core3.global.GlobalManagerAdapter;
import org.gudy.azureus2.core3.global.GlobalManagerListener;
import org.gudy.azureus2.core3.peer.PEPeer;
import org.gudy.azureus2.core3.peer.PEPeerManager;
import org.gudy.azureus2.core3.torrent.TOTorrent;
import org.gudy.azureus2.core3.util.AERunnable;
import org.gudy.azureus2.core3.util.AESemaphore;
import org.gudy.azureus2.core3.util.AsyncDispatcher;
import org.gudy.azureus2.core3.util.BEncoder;
import org.gudy.azureus2.core3.util.ByteFormatter;
import org.gudy.azureus2.core3.util.Debug;
import org.gudy.azureus2.core3.util.FrequencyLimitedDispatcher;
import org.gudy.azureus2.core3.util.LightHashMap;
import org.gudy.azureus2.core3.util.SimpleTimer;
import org.gudy.azureus2.core3.util.SystemTime;
import org.gudy.azureus2.core3.util.TimerEventPerformer;
import org.gudy.azureus2.plugins.peers.Peer;

import com.aelitis.azureus.core.AzureusCore;
import com.aelitis.azureus.core.devices.*;
import com.aelitis.azureus.core.security.CryptoManagerFactory;
import com.aelitis.net.upnp.UPnPDevice;
import com.aelitis.net.upnp.UPnPRootDevice;
import com.aelitis.net.upnp.services.UPnPOfflineDownloader;

public class 
DeviceOfflineDownloaderImpl
	extends DeviceUPnPImpl
	implements DeviceOfflineDownloader
{
	public static final int	UPDATE_MILLIS	= 30*1000;
	public static final int UPDATE_TICKS	= UPDATE_MILLIS/DeviceManagerImpl.DEVICE_UPDATE_PERIOD;
	
	public static final String	client_id = ByteFormatter.encodeString( CryptoManagerFactory.getSingleton().getSecureID());
	
	private volatile UPnPOfflineDownloader		service;
	private volatile String						service_ip;
	
	private volatile boolean					closing;
	
	private AsyncDispatcher	dispatcher = new AsyncDispatcher();
	
	private boolean								start_of_day	= true;
	
	private Map<String,TransferableDownload>	transferable 	= new LinkedHashMap<String,TransferableDownload>();
	private TransferableDownload				current_transfer;
	
	protected
	DeviceOfflineDownloaderImpl(
		DeviceManagerImpl			_manager,
		UPnPDevice					_device,
		UPnPOfflineDownloader		_service )
	{
		super( _manager, _device, Device.DT_OFFLINE_DOWNLOADER );
		
		setService( _service );
	}
	
	protected
	DeviceOfflineDownloaderImpl(
		DeviceManagerImpl	_manager,
		Map					_map )
	
		throws IOException
	{
		super(_manager, _map );
	}
	
	protected boolean
	updateFrom(
		DeviceImpl		_other,
		boolean			_is_alive )
	{
		if ( !super.updateFrom( _other, _is_alive )){
			
			return( false );
		}
		
		if ( !( _other instanceof DeviceOfflineDownloaderImpl )){
			
			Debug.out( "Inconsistent" );
			
			return( false );
		}
		
		DeviceOfflineDownloaderImpl other = (DeviceOfflineDownloaderImpl)_other;
			
		if ( service == null && other.service != null ){
			
			setService( other.service );
			
			updateDownloads();
		}
		
		return( true );
	}
	
	protected void
	setService(
		UPnPOfflineDownloader	_service )
	{
		service	= _service;
		
		UPnPRootDevice root = service.getGenericService().getDevice().getRootDevice();
		
		service_ip = root.getLocation().getHost();
		
		try{
			service_ip = InetAddress.getByName( service_ip ).getHostAddress();
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
		Map cache = root.getDiscoveryCache();
		
		if ( cache != null ){
			
			setPersistentMapProperty( PP_OD_UPNP_DISC_CACHE, cache );
		}
	}
	
	protected void 
	UPnPInitialised() 
	{
		super.UPnPInitialised();
		
		if ( service == null ){
		
			Map	cache = getPersistentMapProperty( PP_OD_UPNP_DISC_CACHE, null );
		
			if ( cache != null ){
			
				getUPnPDeviceManager().injectDiscoveryCache( cache );
			}
		}
	}
	
	protected void 
	updateStatus(
		int tick_count ) 
	{
		super.updateStatus( tick_count );
		
		if ( service == null ){
			
			return;
		}
		
		if ( tick_count % UPDATE_TICKS != 0 ){
			
			return;
		}
		
		updateDownloads();
	}
	
	protected void
	updateDownloads()
	{
		dispatcher.dispatch(
			new AERunnable()
			{
				public void
				runSupport()
				{
					updateDownloadsSupport();
				}
			});
	}
	
	protected void
	updateDownloadsSupport()
	{
		AzureusCore core = getManager().getAzureusCore();
		
		if ( core == null ){
			
			return;
		}

		Map<String,TransferableDownload>	new_transferables = new HashMap<String,TransferableDownload>();
		
		try{
			if ( !isAlive() || service == null || closing ){
				
				return;
			}
			
			Map<String,byte[]>	old_cache 	= (Map<String,byte[]>)getPersistentMapProperty( PP_OD_STATE_CACHE, new HashMap<String,byte[]>());
			
			Map<String,byte[]>	new_cache 	= new HashMap<String, byte[]>();
			
			GlobalManager gm = core.getGlobalManager();
			
			List<DownloadManager> downloads = gm.getDownloadManagers();
			
			if ( start_of_day ){
				
				start_of_day = false;
				
				Map<String,Map> xfer_cache = getPersistentMapProperty( PP_OD_XFER_CACHE, new HashMap<String,Map>());
				
				for ( DownloadManager download: downloads ){

					if ( download.isForceStart()){
						
						TOTorrent torrent = download.getTorrent();
						
						if ( torrent == null ){
							
							continue;
						}
						
						try{
							byte[] hash = torrent.getHash();
							
							String	hash_str = ByteFormatter.encodeString( hash );
							
							Map m = xfer_cache.get( hash_str );
								
							if ( m != null ){
								
								if ( m.containsKey( "f" )){
									
									log( download, "Resetting force-start" );
									
									download.setForceStart( false );
								}
							}
						}catch( Throwable e ){
							
							Debug.printStackTrace(e);
						}
					}
				}
				
				final FrequencyLimitedDispatcher	fld = 
					new FrequencyLimitedDispatcher(
						new AERunnable()
						{
							public void
							runSupport()
							{
								updateDownloads();
							}
						},
						5*1000 );
				
				gm.addListener(
					new GlobalManagerAdapter()
					{
						public void
						downloadManagerAdded(
							DownloadManager	dm )
						{
							fld.dispatch();
						}
							
						public void
						downloadManagerRemoved( 
							DownloadManager	dm )
						{
							fld.dispatch();
						}
					},
					false );
			}
			
			Map<DownloadManager,byte[]>	download_map = new HashMap<DownloadManager, byte[]>();
			
			for ( DownloadManager download: downloads ){
							
				int	state = download.getState();
				
				if ( 	state == DownloadManager.STATE_SEEDING ||
						state == DownloadManager.STATE_ERROR ){
					
					continue;
				}
				
				if ( state == DownloadManager.STATE_STOPPED ){
					
					if ( !download.isPaused()){
						
						continue;
					}
				}
	
				if ( state == DownloadManager.STATE_QUEUED ){
					
					if ( download.isDownloadComplete( false )){
						
						continue;
					}
				}
				
					// download is interesting 
				
				TOTorrent torrent = download.getTorrent();
	
				if ( torrent == null ){
					
					continue;
				}
				
				try{
					byte[] hash = torrent.getHash();
					
					String	hash_str = ByteFormatter.encodeString( hash );
					
					DiskManager disk = download.getDiskManager();
					
					if ( disk == null ){
						
						byte[] existing = old_cache.get( hash_str );
						
						if ( existing != null ){
							
							new_cache.put( hash_str, existing );
							
							download_map.put( download, existing );
						}
					}else{
					
						DiskManagerPiece[] pieces = disk.getPieces();
						
						byte[] needed = new byte[( pieces.length + 7 ) / 8];
						
						int	needed_pos		= 0;
						int	current_byte	= 0;
						int	pos 			= 0;
						
						int	hits = 0;
						
						for ( DiskManagerPiece piece: pieces ){
							
							current_byte = current_byte << 1;
							
							if ( piece.isNeeded() && !piece.isDone()){
								
								current_byte += 1;
								
								hits++;
							}
							
							if (( pos %8 ) == 7 ){
								
								needed[needed_pos++] = (byte)current_byte;
								
								current_byte = 0;
							}
							pos++;
						}
						
						if (( pos % 8 ) != 0 ){
							
							needed[needed_pos++] = (byte)(current_byte << (8 - (pos % 8)));
						}
						
						if ( hits > 0 ){
							
							new_cache.put( hash_str, needed );
							
							download_map.put( download, needed );
						}
					}
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
			
				// store this so we have consistent record for downloads that queue/pause etc and therefore lose accessible piece details
			
			setPersistentMapProperty( PP_OD_STATE_CACHE, new_cache );
			
				// sort by download priority
			
			List<Map.Entry<DownloadManager, byte[]>> entries = new ArrayList<Map.Entry<DownloadManager,byte[]>>( download_map.entrySet());
			
			Collections.sort(
				entries,
				new Comparator<Map.Entry<DownloadManager, byte[]>>()
				{
					public int 
					compare(
						Map.Entry<DownloadManager, byte[]> o1,
						Map.Entry<DownloadManager, byte[]> o2) 
					{
						return( o1.getKey().getPosition() - o2.getKey().getPosition());
					} 
				});
				
			String	download_hashes = "";
			
			Iterator<Map.Entry<DownloadManager, byte[]>> it = entries.iterator();
			
			while( it.hasNext()){
				
				Map.Entry<DownloadManager, byte[]> entry = it.next();
				
				DownloadManager	download = entry.getKey();
				
				try{
					String hash = ByteFormatter.encodeString( download.getTorrent().getHash());
					
					download_hashes += ( download_hashes.length()==0?"":"," ) + hash;
					
				}catch( Throwable e ){
					
					log( download, "Failed to get download hash", e );
					
					it.remove();
				}
			}
			
			try{
				String[] set_dl_results = service.setDownloads( client_id, download_hashes );
				
				String	set_dl_result	= set_dl_results[0];
				String	set_dl_status 	= set_dl_results[1];
				
				if ( !set_dl_status.equals( "OK" )){
					
					throw( new Exception( "Failing result returned: " + set_dl_status ));
				}
				
				String[]	bits = set_dl_result.split( "," );
				
				if ( bits.length != entries.size()){
					
					log( "SetDownloads returned an invalid number of results (hashes=" + entries.size() + ",result=" + set_dl_result );
					
				}else{
					
					it = entries.iterator();
					
					int	pos = 0;
					
					while( it.hasNext()){
						
						Map.Entry<DownloadManager, byte[]> entry = it.next();
						
						DownloadManager	download = entry.getKey();
						
						try{
							TOTorrent torrent = download.getTorrent();
	
							String hash_str = ByteFormatter.encodeString( torrent.getHash());
							
							int	status = Integer.parseInt( bits[ pos++ ]);
		
							boolean	do_update = false;
						
							if ( status == 0 ){
							
								do_update = true;
							
							}else if ( status == 1 ){
							
									// need to add the torrent
							
								try{
							
									String add_result = 
										service.addDownload( 
											client_id, 
											hash_str,
											ByteFormatter.encodeStringFully( BEncoder.encode( torrent.serialiseToMap())));
									
									log( download, "AddDownload succeeded" );
									
									if ( add_result.equals( "OK" )){
										
										do_update = true;
										
									}else{
										
										throw( new Exception( "Failed to add download: " + add_result ));
									}
								}catch( Throwable e ){
									
										// TODO: prevent continual attempts to add same torrent?
									
									log( download, "Failed to add download", e );
								}
							}else{
							
								log( download, "SetDownloads: error status returned - " + status );
							}
					
							if ( do_update ){
					
								try{
									byte[]	required_map = entry.getValue();
									
									String	required_bitfield = ByteFormatter.encodeStringFully( required_map );
									
									String[] update_results = 
										service.updateDownload( 
											client_id, 
											hash_str,
											required_bitfield );
										
									String	have_bitfield	= update_results[0];
									String	update_status 	= update_results[1];
									
									if ( !update_status.equals( "OK" )){
										
										throw( new Exception( "UpdateDownload: Failing result returned: " + update_status ));
									}
												
									int	useful_piece_count = 0;

									if ( have_bitfield.length() > 0 ){
										
										byte[]	have_map = ByteFormatter.decodeString( have_bitfield );
										
										if ( have_map.length != required_map.length ){
											
											throw( new Exception( "UpdateDownload: Returned bitmap length invalid" ));
										}
										
										for ( int i=0;i<required_map.length;i++){
											
											int x = ( required_map[i] & have_map[i] )&0xff;
											
											if ( x != 0 ){
													
												for (int j=0;j<8;j++){
													
													if ((x&0x01) != 0 ){
														
														useful_piece_count++;
													}
													
													x >>= 1;
												}
											}
										}
										
										if ( useful_piece_count > 0 ) {
										
											new_transferables.put( hash_str, new TransferableDownload( download, hash_str, have_map  ));
										}
									}
									
									if ( useful_piece_count > 0 ){
									
										log( download, "They have " + useful_piece_count + " pieces that we don't" );
									}
									
								}catch( Throwable e ){
							
									log( download, "UpdateDownload failed", e );
								}
							}
						}catch( Throwable e ){
						
							log( download, "Processing failed", e );
						}
					}
				}
				
			}catch( Throwable e ){
				
				log( "SetDownloads failed", e );
			}
		}finally{
			
			updateTransferable( new_transferables );
		}
	}

	protected void
	updateTransferable(
		Map<String,TransferableDownload>	map )
	{
			// remove non-transferable entries
		
		Iterator<Map.Entry<String,TransferableDownload>>	it = transferable.entrySet().iterator();
		
		while( it.hasNext()){
			
			Map.Entry<String,TransferableDownload> entry = it.next();
			
			if ( !map.containsKey( entry.getKey())){
					
				TransferableDownload existing = entry.getValue();
				
				if ( existing == current_transfer ){
					
					current_transfer.deactivate();
						
					current_transfer = null;
				}
				
				it.remove();
			}
		}
		
			// add in new ones
		
		for ( TransferableDownload td: map.values()){
			
			String hash = td.getHash();
			
			if ( !transferable.containsKey( hash )){
				
				transferable.put( hash, td );
			}
		}
		
		if ( transferable.size() == 0 ){
			
			return;
		}
		
			// check current
		
		if ( current_transfer != null && transferable.size() > 0 ){
			
				// rotate through them in case something's stuck for whatever reason
			
			long	now = SystemTime.getMonotonousTime();
			
			long	runtime = now - current_transfer.getStartTime();
						
			if ( runtime >= 30*1000 ){
				
				boolean	rotate = false;

				PEPeerManager pm = current_transfer.getDownload().getPeerManager();
				
				if ( pm == null ){
					
					rotate = true;
					
				}else{
					
					if ( runtime > 3*60*1000 ){
					
						List<PEPeer> peers = pm.getPeers( service_ip );
						
						if ( peers.size() == 0 ){
							
							rotate = true;
							
						}else{
						
							PEPeer peer = peers.get(0);
							
							if ( peer.getStats().getDataReceiveRate() < 1024 ){
								
								rotate = true;
							}
						}
					}
				}
				
				if ( rotate ){
					
					current_transfer.deactivate();
					
					current_transfer = null;
				}
			}
		}
		
		if ( current_transfer == null ){
			
			Iterator<TransferableDownload> it2 = transferable.values().iterator();
			
			current_transfer = it2.next();
			
			it2.remove();
			
			transferable.put( current_transfer.getHash(), current_transfer );
		}
		
		if ( current_transfer != null ){
			
			if ( !current_transfer.isActive()){
				
				current_transfer.activate();
			}
			
			if ( current_transfer.isForced()){
				
				Map<String,Map> xfer_cache = new HashMap<String,Map>();
				
				Map m = new HashMap();
				
				m.put( "f", new Long(1));
				
				xfer_cache.put( current_transfer.getHash(), m );
				
				setPersistentMapProperty( PP_OD_XFER_CACHE, xfer_cache );
			}
			
			DownloadManager	download = current_transfer.getDownload();
			
			int	data_port = current_transfer.getDataPort();
			
			if ( data_port <= 0 ){
								
				try{
					String[] start_results = service.startDownload( client_id, current_transfer.getHash());
					
					String start_status = start_results[1];
					
					if ( !start_status.equals( "OK" )){
						
						throw( new Exception( "Failing result returned: " + start_status ));
					}
					
					data_port = Integer.parseInt( start_results[0] );
					
					log( download, "StartDownload succeeded - data port=" + data_port );

				}catch( Throwable e ){
					
					log( download, "StartDownload failed", e );
				}
			}
			
			if ( data_port > 0 ){
				
				current_transfer.setDataPort( data_port );
			}
			
			final TransferableDownload transfer = current_transfer;

			dispatcher.dispatch(
				new AERunnable()
				{
					private final int[]	count = { 0 };
					
					public void
					runSupport()
					{
						count[0]++;
						
						if ( current_transfer != transfer || !transfer.isActive()){
							
							return;
						}
						
						PEPeerManager pm = transfer.getDownload().getPeerManager();
						
						if ( pm == null ){
							
							return;
						}
						
						List<PEPeer> peers = pm.getPeers( service_ip );
								
						if ( peers.size() > 0 ){
			
							return;
						}
						
						Map	user_data = new LightHashMap();
												
						user_data.put( Peer.PR_PRIORITY_CONNECTION, new Boolean( true ));
						
						pm.addPeer( service_ip, transfer.getDataPort(), 0, false, user_data );
						
						if ( count[0] < 3 ){
							
							final AERunnable target = this;
							
							SimpleTimer.addEvent(
								"OD:retry",
								SystemTime.getCurrentTime()+5*1000,
								new TimerEventPerformer()
								{
									public void 
									perform(
										org.gudy.azureus2.core3.util.TimerEvent event ) 
									{
										dispatcher.dispatch( target );
									};
								});
						}
					}
				});
		}
	}
	
	protected void
	close()
	{
		super.close();
	
		final AESemaphore sem = new AESemaphore( "DOD:closer" );
		
		dispatcher.dispatch(
			new AERunnable()
			{
				public void 
				runSupport() 
				{
					try{
						closing	= true;
						
						if ( service != null ){
							
							try{
								service.activate( client_id );
								
							}catch( Throwable e ){
								
							}
						}
					}finally{
						
						sem.release();
					}
				}
			});
		
		sem.reserve(250);
	}
	
	protected void
	log(
		DownloadManager		download,	
		String				str )
	{
		log( download.getDisplayName() + ": " + str );
	}
	
	protected void
	log(
		DownloadManager		download,	
		String				str,
		Throwable			e )
	{
		log( download.getDisplayName() + ": " + str, e );
	}
	
	protected void
	log(
		String	str )
	{
		super.log( "OfflineDownloader: " + str );
	}
	
	protected void
	log(
		String		str,
		Throwable	e )
	{
		super.log( "OfflineDownloader: " + str, e );
	}
	
	protected class
	TransferableDownload
	{
		private DownloadManager		download;
		private String				hash_str;
		private byte[]				have_map;
		
		private boolean				active;
		private long				start_time;	
		private boolean				forced;
		
		private int					data_port;
		
		protected
		TransferableDownload(
			DownloadManager		_download,
			String				_hash_str,
			byte[]				_have_map )
		{
			download		= _download;
			hash_str		= _hash_str;
			have_map		= _have_map;
		}
		
		protected long
		getStartTime()
		{
			return( start_time );
		}
		
		protected boolean
		isForced()
		{
			return( forced );
		}
		
		protected boolean
		isActive()
		{
			return( active );
		}
		
		protected int
		getDataPort()
		{
			return( data_port );
		}
		
		protected void
		setDataPort(
			int		dp )
		{
			data_port = dp;
		}
		
		protected void
		activate()
		{
			active		= true;		
			start_time 	= SystemTime.getMonotonousTime();
			
			if ( download.isForceStart()){

				log( download, "Activating for transfer" );
				
			}else{
				
				log( download, "Activating for transfer; setting force-start" );

				forced = true;
								
				download.setForceStart( true );
			}
		}
		
		protected void
		deactivate()
		{
			active = false;
			
			if ( forced ){

				log( download, "Deactivating for transfer; resetting force-start" );
	
				download.setForceStart( false );
				
			}else{
				
				log( download, "Deactivating for transfer" );
			}
			
			data_port	= 0;
		}
		
		protected DownloadManager
		getDownload()
		{
			return( download );
		}
		
		protected String
		getHash()
		{
			return( hash_str );
		}
		
		protected byte[]
		getHaveMap()
		{
			return( have_map );
		}
	}
}
