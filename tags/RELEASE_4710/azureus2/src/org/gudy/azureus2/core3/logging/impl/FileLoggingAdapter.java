/*
 * Created on Feb 28, 2006 2:41:30 PM
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
 */
package org.gudy.azureus2.core3.logging.impl;

import org.gudy.azureus2.core3.logging.LogEvent;

/**
 * Listeners for FileLogging
 * 
 * @author TuxPaper
 * @created Feb 28, 2006
 *
 */
public class FileLoggingAdapter {
	/**
	 * Called when we are about to log an event to file.
	 * 
	 * @param event Event being logged to file
	 * @param lineOut line to be written to file (modifyable)
	 * @return true-ok to log to file; false-skip logging to file
	 */
	public boolean logToFile(LogEvent event, StringBuffer lineOut) {
		return true;
	}
}
