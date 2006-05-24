/*
 * File    : TorrentManager.java
 * Created : 28-Feb-2004
 * By      : parg
 * 
 * Azureus - a Java Bittorrent client
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.gudy.azureus2.plugins.torrent;

/**
 * @author parg
 *
 */

import java.net.URL;
import java.io.File;
import java.io.InputStream;

public interface 
TorrentManager 
{
	public TorrentDownloader
	getURLDownloader(
		URL		url )
	
		throws TorrentException;
	
	public TorrentDownloader
	getURLDownloader(
		URL		url,
		String	user_name,
		String	password )
	
		throws TorrentException;
	
		/**
		 * decodes a torrent encoded using the normal "bencoding" rules from a file
		 * @param file
		 * @return
		 * @throws TorrentException
		 */
	
	public Torrent
	createFromBEncodedFile(
		File		file )
	
		throws TorrentException;
	
		/**
		 * decodes a torrent encoded using the normal "bencoding" rules from a file but discards the piece
		 * hashes to save memory. note that this means that if something reads the hashes they will be
		 * re-read from the torrent file and if the file has since been deleted things go smelly
		 * @param file
		 * @param for_seeding reduces memory usage by discarding piece hashes
		 * @return
		 */
	
	public Torrent
	createFromBEncodedFile(
		File		file,
		boolean		for_seeding )
	
		throws TorrentException;
	
		/**
		 * decodes a torrent encoded using the normal "bencoding" rules from an InputStream
		 * @param file
		 * @return
		 * @throws TorrentException
		 */
	
	public Torrent
	createFromBEncodedInputStream(
		InputStream		data )
	
		throws TorrentException;
	
	/**
	 * decodes a torrent encoded using the normal "bencoding" rules from a byte array
	 * @param file
	 * @return
	 * @throws TorrentException
	 */
	
	public Torrent
	createFromBEncodedData(
		byte[]		data )
	
		throws TorrentException;
	
		/**
		 * creates a new torrent from an input file
		 * @param data
		 * @return
		 * @throws TorrentException
		 */
	
	public Torrent
	createFromDataFile(
		File		data,
		URL			announce_url )
	
		throws TorrentException;
	
	public Torrent
	createFromDataFile(
		File		data,
		URL			announce_url,
		boolean		include_other_hashes )
	
		throws TorrentException;
	
		/**
		 * Gives access to the currently defined torrent attributes. As of 2.1.0.2
		 * only "category" is defined, however in the future more could be added
		 * such as "quality", "bit rate" etc.
		 * @return
		 */
	
	public TorrentAttribute[]
	getDefinedAttributes();
	
		/**
		 * Gives access to the standard built-in attributes
		 * @param name
		 * @return
		 */
	
	public TorrentAttribute
	getAttribute(
		String		name );
	
		/**
		 * Gives access to/creates arbitrary String attributes for plugins to use
		 * @param name
		 * @return
		 */
	
	public TorrentAttribute
	getPluginAttribute(
		String		name );
	
	public void
	addListener(
		TorrentManagerListener	l );
	
	public void
	removeListener(
		TorrentManagerListener	l );
}
