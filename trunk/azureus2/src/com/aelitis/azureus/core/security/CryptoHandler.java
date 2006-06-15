/*
 * Created on 15 Jun 2006
 * Created by Paul Gardner
 * Copyright (C) 2006 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * AELITIS, SAS au capital de 46,603.30 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */

package com.aelitis.azureus.core.security;

public interface 
CryptoHandler 
{
		/**
		 * Explicit unlock request
		 * @param password
		 * @throws CryptoManagerException
		 */
	
	public void
	unlock(
		char[]		password )
	
		throws CryptoManagerException;
		
		/**
		 * Puts the handler back into a state where password will be required to access private stuff 
		 */
	
	public void
	lock();
	
	public int
	getUnlockTimeoutSeconds();
	
		/**
		 * 
		 * @param secs		0-> infinite
		 */
	
	public void
	setUnlockTimeoutSeconds(
		int		secs );
	
		/**
		 * 
		 * @param data
		 * @param password		null -> password listener is asked for password IF required
		 * @param reason
		 * @return
		 * @throws CryptoManagerException
		 */
	
	public byte[]
	sign(
		byte[]		data,
		char[]		password,
		String		reason )
	
		throws CryptoManagerException;
	
	public boolean
	verify(
		byte[]		public_key,
		byte[]		data,
		byte[]		signature )
	
		throws CryptoManagerException;

		/**
		 * 
		 * @param password		null -> password listener is asked for password IF required
		 * @param reason
		 * @return
		 * @throws CryptoManagerException
		 */
	
	public byte[]
	getPublicKey(
		char[]		password,
		String		reason )
	
		throws CryptoManagerException;

		/**
		 * 
		 * @param password		null -> password listener is asked for password IF required
		 * @param reason
		 * @return
		 * @throws CryptoManagerException
		 */
	
	public byte[]
	getEncryptedPrivateKey(
		char[]		password,
		String		reason )
	
		throws CryptoManagerException;
	
	public void
	recoverKeys(
		byte[]		public_key,
		byte[]		encrypted_private_key )
	
		throws CryptoManagerException;
	
	public void
	resetKeys(
		char[]		password )
	
		throws CryptoManagerException;
	
	public void
	changePassword(
		char[]		old_password,
		char[]		new_password )
	
		throws CryptoManagerException;
}
