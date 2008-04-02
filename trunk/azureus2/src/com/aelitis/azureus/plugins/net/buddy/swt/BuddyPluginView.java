/*
 * Created on Mar 19, 2008
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


package com.aelitis.azureus.plugins.net.buddy.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.gudy.azureus2.ui.swt.mainwindow.Colors;
import org.gudy.azureus2.ui.swt.plugins.UISWTViewEvent;
import org.gudy.azureus2.ui.swt.plugins.UISWTViewEventListener;

import com.aelitis.azureus.plugins.net.buddy.BuddyPlugin;
import com.aelitis.azureus.plugins.net.buddy.BuddyPluginListener;

public class 
BuddyPluginView
	implements UISWTViewEventListener, BuddyPluginListener
{
	private BuddyPlugin	plugin;
	
	private boolean		created = false;	

	private Composite	composite;
	private StyledText 	log;
		
	private static final int LOG_NORMAL 	= 1;
	private static final int LOG_SUCCESS 	= 2;
	private static final int LOG_ERROR 		= 3;

	public
	BuddyPluginView(
		BuddyPlugin		_plugin )
	{
		plugin	= _plugin;
		
		plugin.addListener( this );
	}
	
	public boolean 
	eventOccurred(
		UISWTViewEvent event )
	{
		switch( event.getType() ){

			case UISWTViewEvent.TYPE_CREATE:{
				
				if ( created ){
					
					return( false );
				}
				
				created = true;
				
				break;
			}
			case UISWTViewEvent.TYPE_INITIALIZE:{
				
				initialise((Composite)event.getData());
				
				break;
			}
			case UISWTViewEvent.TYPE_CLOSE:
			case UISWTViewEvent.TYPE_DESTROY:{
				
				try{
					destroy();
					
				}finally{
					
					created = false;
				}
				
				break;
			}
		}
		
		return true;
	}
	
	protected void
	initialise(
		Composite	_composite )
	{
		composite	= _composite;
		
		Composite main = new Composite(composite, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.numColumns = 1;
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		main.setLayout(layout);
		GridData grid_data = new GridData(GridData.FILL_BOTH );
		main.setLayoutData(grid_data);
		
		
		
		
			// log area
		
		log = new StyledText(main,SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		grid_data = new GridData(GridData.FILL_BOTH);
		grid_data.horizontalSpan = 1;
		grid_data.horizontalIndent = 4;
		log.setLayoutData(grid_data);
		log.setIndent( 4 );
		
		print( "Plugin initialised" );
	}
	
	public void
	messageLogged(
		String		str )
	{
		print( str, LOG_NORMAL, false, false );
	}
	
	protected void
	print(
		String		str )
	{
		print( str, LOG_NORMAL, false, true );
	}
	
	protected void
	print(
		final String		str,
		final int			log_type,
		final boolean		clear_first,
		boolean				log_to_plugin )
	{
		if ( log_to_plugin ){
		
			plugin.log( str );
		}
		
		if ( !log.isDisposed()){
			
			final int f_log_type = log_type;
			
			log.getDisplay().asyncExec(
					new Runnable()
					{
						public void
						run()
						{
							if ( log.isDisposed()){
								
								return;
							}
							
							int	start;
							
							if ( clear_first ){
							
								start	= 0;
								
								log.setText( str + "\n" );
								
							}else{
							
								start = log.getText().length();
								
								log.append( str + "\n" );
							}
							
							Color 	color;
							
							if ( f_log_type == LOG_NORMAL ){
								
								color = Colors.black;
								
							}else if ( f_log_type == LOG_SUCCESS ){
								
								color = Colors.green;
								
							}else{
								
								color = Colors.red;
							}
							
							StyleRange styleRange = new StyleRange();
							styleRange.start = start;
							styleRange.length = str.length();
							styleRange.foreground = color;
							log.setStyleRange(styleRange);
							
							log.setSelection( log.getText().length());
						}
					});
		}
	}

	protected void
	destroy()
	{
		composite = null;
	}
}
