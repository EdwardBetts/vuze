package org.gudy.azureus2.ui.swt.views.table.painted;

import java.util.*;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import org.gudy.azureus2.core3.config.ParameterListener;
import org.gudy.azureus2.core3.internat.MessageText;
import org.gudy.azureus2.core3.internat.MessageText.MessageTextListener;
import org.gudy.azureus2.core3.util.*;
import org.gudy.azureus2.plugins.ui.tables.TableColumn;
import org.gudy.azureus2.plugins.ui.tables.TableRowMouseEvent;
import org.gudy.azureus2.plugins.ui.tables.TableRowMouseListener;
import org.gudy.azureus2.ui.swt.MenuBuildUtils;
import org.gudy.azureus2.ui.swt.SimpleTextEntryWindow;
import org.gudy.azureus2.ui.swt.Utils;
import org.gudy.azureus2.ui.swt.debug.ObfusticateImage;
import org.gudy.azureus2.ui.swt.mainwindow.Colors;
import org.gudy.azureus2.ui.swt.mainwindow.HSLColor;
import org.gudy.azureus2.ui.swt.plugins.UISWTViewEvent;
import org.gudy.azureus2.ui.swt.pluginsimpl.UISWTViewCore;
import org.gudy.azureus2.ui.swt.shells.GCStringPrinter;
import org.gudy.azureus2.ui.swt.views.table.*;
import org.gudy.azureus2.ui.swt.views.table.impl.*;

import com.aelitis.azureus.ui.common.table.*;
import com.aelitis.azureus.ui.common.table.TableViewFilterCheck;
import com.aelitis.azureus.ui.common.table.impl.TableColumnManager;
import com.aelitis.azureus.ui.common.table.impl.TableRowCoreSorter;
import com.aelitis.azureus.ui.common.table.impl.TableViewImpl;
import com.aelitis.azureus.ui.swt.utils.ColorCache;
import com.aelitis.azureus.ui.swt.utils.FontUtils;

/**
 * A TableView implemented by painting on a canvas
 * 
 * TODO: 
 * Keyboard Selection
 * Cursor
 * Sub Rows
 * Column move and resize past bounds
 */
public class TableViewPainted
	extends TableViewImpl<Object>
	implements ParameterListener, TableViewSWT<Object>, ObfusticateImage,
	MessageTextListener
{

	private static final boolean DEBUG_ROWCHANGE = false;

	private Composite cTable;

	private int loopFactor;

	/** How often graphic cells get updated
	 */
	protected int graphicsUpdate = configMan.getIntParameter("Graphics Update");

	protected int reOrderDelay = configMan.getIntParameter("ReOrder Delay");

	private int defaultRowHeight = 16;

	/**
	 * Rows visible to user.  We assume this list is always up to date
	 */
	TableRowPainted[] visibleRows = new TableRowPainted[0];

	/**
	 * Up to date table client area.  So far, the best places to refresh
	 * this variable are in the PaintItem event and the scrollbar's events.
	 * Typically table.getClientArea() is time consuming 
	 */
	protected Rectangle clientArea;

	private int lastHorizontalPos;

	private boolean columnVisibilitiesChanged = true;

	private boolean isVisible;

	private Shell shell;

	private Color colorLine;

	private int headerHeight = 40;

	private Canvas cHeaderArea;

	private Image canvasImage;

	//private ScrollBar verticalBar;

	private final String sDefaultSortOn;

	private TableViewSWT_Common tvSWTCommon;

	private TableViewSWT_TabsCommon tvTabsCommon;

	private TableViewSWTPanelCreator mainPanelCreator;

	private boolean isMultiSelect;

	private int columnsWidth;

	//private ScrollBar horizontalBar;

	private Menu menu;

	protected boolean isHeaderDragging;

	private TableRowPainted focusedRow;

	private boolean enableTabViews;

	protected boolean isDragging;

	private Composite mainComposite;

	private ScrolledComposite sc;

	private int totalHeight = 0;

	private boolean redrawTableScheduled;

	private int visibleRowsHeight;

	private Font font70pct;

	/*
		class RefreshTableRunnable extends AERunnable {
			private boolean forceSort;
			public void runSupport() {
				__refreshTable(isForceSort());
			}
			public boolean isForceSort() {
				return forceSort;
			}
			public void setForceSort(boolean forceSort) {
				this.forceSort = forceSort;
			}
		}
		
		private RefreshTableRunnable refreshTableRunnable = new RefreshTableRunnable();
	*/

	/**
	 * Main Initializer
	 * @param _sTableID Which table to handle (see 
	 *                   {@link org.gudy.azureus2.plugins.ui.tables.TableManager}).
	 *                   Config settings are stored with the prefix of  
	 *                   "Table.<i>TableID</i>"
	 * @param _sPropertiesPrefix Prefix for retrieving text from the properties
	 *                            file (MessageText).  Typically 
	 *                            <i>TableID</i> + "View"
	 * @param _basicItems Column Definitions
	 * @param _sDefaultSortOn Column name to sort on if user hasn't chosen one yet
	 * @param _iTableStyle SWT style constants used when creating the table
	 */
	public TableViewPainted(Class<?> pluginDataSourceType, String _sTableID,
			String _sPropertiesPrefix, TableColumnCore[] _basicItems,
			String _sDefaultSortOn, int _iTableStyle) {
		super(pluginDataSourceType, _sTableID, _sPropertiesPrefix, _basicItems);
		//		boolean wantTree = (_iTableStyle & SWT.CASCADE) != 0;
		//		_iTableStyle &= ~SWT.CASCADE;
		//		if (wantTree) {
		//			useTree = COConfigurationManager.getBooleanParameter("Table.useTree")
		//					&& !Utils.isCarbon;
		//		}
		//		basicItems = _basicItems;
		//		sDefaultSortOn = _sDefaultSortOn;
		//		iTableStyle = _iTableStyle | SWT.V_SCROLL | SWT.DOUBLE_BUFFERED;
		this.sDefaultSortOn = _sDefaultSortOn;
		this.isMultiSelect = (_iTableStyle & SWT.MULTI) > 0;

		// Deselect rows if user clicks on a blank spot (a spot with no row)
		tvSWTCommon = new TableViewSWT_Common(this) {
			public void widgetSelected(SelectionEvent event) {
				//updateSelectedRows(table.getSelection(), true);
			}

			@Override
			public void mouseDown(TableRowSWT row, TableCellCore cell, int button,
					int stateMask) {
				if (row == null) {
					return;
				}
				int keyboardModifier = (stateMask & SWT.MODIFIER_MASK);
				if ((keyboardModifier & (SWT.MOD1 | SWT.MOD4)) > 0) {
					// ALT (Win) .. add selection
					// do nothing because caller will select it (!)
				} else if ((keyboardModifier & SWT.SHIFT) > 0) {
					// select from focus to row
					// TODO: Fix to work with subrows
					TableRowCore[] selectedRows = getSelectedRows();
					TableRowCore firstRow = selectedRows.length > 0 ? selectedRows[0]
							: getRow(0);
					int startPos = indexOf(firstRow);
					int endPos = indexOf(row);
					if (startPos > endPos) {
						int i = endPos;
						endPos = startPos;
						startPos = i;
					}
					int size = endPos - startPos;
					TableRowCore[] rows = new TableRowCore[size];
					for (int i = 0; i < size; i++) {
						rows[i] = getRow(i + startPos);
					}
					setSelectedRows(rows);
				} else {
					setSelectedRows(new TableRowCore[] {
						row
					});
				}
			}

			@Override
			public void keyPressed(KeyEvent event) {
				if (event.keyCode == SWT.ARROW_UP) {
					TableRowCore rowToSelect = null;
					if (focusedRow != null) {
						TableRowCore parentRow = focusedRow.getParentRowCore();
						if (parentRow == null) {
							TableRowCore row = getRow(indexOf(focusedRow) - 1);
							if (row != null && row.isExpanded() && row.getSubItemCount() > 0) {
								rowToSelect = row.getSubRow(row.getSubItemCount() - 1);
							} else {
								rowToSelect = row;
							}
						} else {
							int index = focusedRow.getIndex();
							if (index > 0) {
								rowToSelect = parentRow.getSubRow(index - 1);
							} else {
								rowToSelect = parentRow;
							}
						}
					}
					if (rowToSelect == null) {
						rowToSelect = getRow(0);
					}

					if ((event.stateMask & SWT.SHIFT) > 0) {
						if (rowToSelect != null) {
							TableRowCore[] selectedRows = getSelectedRows();
							Arrays.sort(selectedRows, new TableRowCoreSorter());
							boolean select = selectedRows.length == 0
									|| selectedRows[0] == focusedRow;
//							System.out.println("i=" + selectedRows[0].getIndex() + ";"
//									+ select + ";" + focusedRow.getIndex());
							if (select) {
								rowToSelect.setSelected(select);
							} else {
								focusedRow.setSelected(false);
								setFocusedRow(rowToSelect);
							}
						}
					} else {
						setSelectedRows(new TableRowCore[] {
							rowToSelect
						});
					}
				} else if (event.keyCode == SWT.ARROW_DOWN) {
					TableRowCore rowToSelect = null;
					if (focusedRow == null) {
						rowToSelect = getRow(0);
					} else {
						if (focusedRow.isExpanded() && focusedRow.getSubItemCount() > 0) {
							TableRowCore[] subRowsWithNull = focusedRow.getSubRowsWithNull();
							for (TableRowCore row : subRowsWithNull) {
								if (row != null) {
									rowToSelect = row;
									break;
								}
							}
							if (rowToSelect == null) {
								rowToSelect = getRow(focusedRow.getIndex() + 1);
							}
						} else {
							TableRowCore parentRow = focusedRow.getParentRowCore();
							if (parentRow != null) {
								rowToSelect = parentRow.getSubRow(focusedRow.getIndex() + 1);

								if (rowToSelect == null) {
									rowToSelect = getRow(parentRow.getIndex() + 1);
								}
							} else {
								rowToSelect = getRow(focusedRow.getIndex() + 1);
							}
						}
					}

					if (rowToSelect != null) {
						if ((event.stateMask & SWT.SHIFT) > 0) {
							TableRowCore[] selectedRows = getSelectedRows();
							Arrays.sort(selectedRows, new TableRowCoreSorter());
							boolean select = selectedRows.length == 0
									|| selectedRows[selectedRows.length - 1] == focusedRow;
							if (select) {
								rowToSelect.setSelected(select);
							} else {
								focusedRow.setSelected(false);
								setFocusedRow(rowToSelect);
							}
						} else {
							setSelectedRows(new TableRowCore[] {
								rowToSelect
							});
						}
					}
				} else if (event.keyCode == SWT.ARROW_RIGHT) {
					if (focusedRow != null && !focusedRow.isExpanded() && canHaveSubItems()) {
						focusedRow.setExpanded(true);
					}
				} else if (event.keyCode == SWT.ARROW_LEFT) {
					if (focusedRow != null && focusedRow.isExpanded() && canHaveSubItems()) {
						focusedRow.setExpanded(false);
					}
				}
				super.keyPressed(event);
			}

			@Override
			public void keyReleased(KeyEvent e) {
				swt_calculateClientArea();
				visibleRowsChanged();

				super.keyReleased(e);
			}
		};
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#clipboardSelected()
	 */
	public void clipboardSelected() {
		String sToClipboard = "";
		TableColumnCore[] visibleColumns = getVisibleColumns();
		for (int j = 0; j < visibleColumns.length; j++) {
			if (j != 0) {
				sToClipboard += "\t";
			}
			String title = MessageText.getString(visibleColumns[j].getTitleLanguageKey());
			sToClipboard += title;
		}

		TableRowCore[] rows = getSelectedRows();
		for (TableRowCore row : rows) {
			sToClipboard += "\n";
			for (int j = 0; j < visibleColumns.length; j++) {
				TableColumnCore column = visibleColumns[j];
				if (j != 0) {
					sToClipboard += "\t";
				}
				TableCellCore cell = row.getTableCellCore(column.getName());
				if (cell != null) {
					sToClipboard += cell.getClipboardText();
				}
			}
		}
		new Clipboard(getComposite().getDisplay()).setContents(new Object[] {
			sToClipboard
		}, new Transfer[] {
			TextTransfer.getInstance()
		});
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#isDisposed()
	 */
	public boolean isDisposed() {
		return cTable == null || cTable.isDisposed();
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#refreshTable(boolean)
	 */
	public void refreshTable(final boolean bForceSort) {
		__refreshTable(bForceSort);
		//refreshTableRunnable.setForceSort(bForceSort);
		//Utils.getOffOfSWTThread(refreshTableRunnable);
	}

	public void __refreshTable(boolean bForceSort) {
		long lStart = SystemTime.getCurrentTime();
		super.refreshTable(bForceSort);

		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				// call to trigger invalidation if visibility changes
				isVisible();
			}
		});
		final boolean bDoGraphics = (loopFactor % graphicsUpdate) == 0;
		final boolean bWillSort = bForceSort || (reOrderDelay != 0)
				&& ((loopFactor % reOrderDelay) == 0);
		//System.out.println("Refresh.. WillSort? " + bWillSort);

		if (bWillSort) {
			TableColumnCore sortColumn = getSortColumn();
			if (bForceSort && sortColumn != null) {
				resetLastSortedOn();
				sortColumn.setLastSortValueChange(SystemTime.getCurrentTime());
			}
			_sortColumn(true, false, false);
		}

		runForAllRows(new TableGroupRowVisibilityRunner() {
			public void run(TableRowCore row, boolean bVisible) {
				row.refresh(bDoGraphics, bVisible);
			}
		});
		loopFactor++;

		long diff = SystemTime.getCurrentTime() - lStart;
		if (diff > 0) {
			//debug("refreshTable took " + diff);
		}

		if (tvTabsCommon != null) {
			tvTabsCommon.refresh();
		}
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#setEnableTabViews(boolean)
	 */
	public void setEnableTabViews(boolean enableTabViews) {
		this.enableTabViews = enableTabViews;
	}

	public boolean isTabViewsEnabled() {
		return enableTabViews;
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#setFocus()
	 */
	public void setFocus() {
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				if (isDisposed()) {
					return;
				}
				cTable.setFocus();
			}
		});
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#setRowDefaultHeight(int)
	 */
	public void setRowDefaultHeight(int iHeight) {
		defaultRowHeight = iHeight;
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#getRow(int, int)
	 */
	public TableRowCore getRow(int x, int y) {
		if (visibleRows.length == 0) {
			return null;
		}
		int curY = visibleRows[0].getDrawOffset().y;
		for (TableRowCore row : visibleRows) {
			int h = ((TableRowPainted) row).getHeight();
			if (y >= curY && y < curY + h) {
				return row;
			}
			curY += h;
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#isRowVisible(com.aelitis.azureus.ui.common.table.TableRowCore)
	 */
	public boolean isRowVisible(TableRowCore row) {
		if (row == null) {
			return false;
		}
		synchronized (visibleRows) {
			for (TableRowCore visibleRow : visibleRows) {
				if (visibleRow == row) {
					return true;
				}
			}
			return false;
			//			if (visibleRows.length == 0) {
			//				return false;
			//			}
			//			int i = row.getIndex();
			//			return i >= visibleRows[0].getIndex()
			//					&& i <= visibleRows[visibleRows.length - 1].getIndex();
		}
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#getTableCellWithCursor()
	 */
	public TableCellCore getTableCellWithCursor() {
		// TODO: Make work outside SWT?
		Point pt = cTable.getDisplay().getCursorLocation();
		pt = cTable.toControl(pt);
		return getTableCell(pt.x, pt.y);
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#getTableRowWithCursor()
	 */
	public TableRowCore getTableRowWithCursor() {
		// TODO: Make work outside SWT?
		Point pt = cTable.getDisplay().getCursorLocation();
		pt = cTable.toControl(pt);
		return getTableRow(pt.x, pt.y, true);
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#getRowDefaultHeight()
	 */
	public int getRowDefaultHeight() {
		return defaultRowHeight;
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#setEnabled(boolean)
	 */
	public void setEnabled(final boolean enable) {
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				if (!isDisposed()) {
					cTable.setEnabled(enable);
				}
			}
		});
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#canHaveSubItems()
	 */
	public boolean canHaveSubItems() {
		return true;
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#setHeaderVisible(boolean)
	 */
	public void setHeaderVisible(final boolean visible) {
		super.setHeaderVisible(visible);

		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				if (cHeaderArea != null && !cHeaderArea.isDisposed()) {
					cHeaderArea.setVisible(visible);
					FormData fd = Utils.getFilledFormData();
					fd.height = visible ? headerHeight : 1;
					fd.bottom = null;
					cHeaderArea.setLayoutData(fd);
					cHeaderArea.getParent().layout(true);
				}
			}
		});
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#getMaxItemShown()
	 */
	public int getMaxItemShown() {
		// NOT USED
		return 0;
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableView#setMaxItemShown(int)
	 */
	public void setMaxItemShown(int newIndex) {
		// NOT USED
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.core3.internat.MessageText.MessageTextListener#localeChanged(java.util.Locale, java.util.Locale)
	 */
	public void localeChanged(Locale old_locale, Locale new_locale) {
		Utils.execSWTThreadLater(0, new AERunnable() {
			public void runSupport() {
				if (tvTabsCommon != null) {
					tvTabsCommon.localeChanged();
				}

				tableInvalidate();
				refreshTable(true);
				cHeaderArea.redraw();
			}
		});
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableStructureModificationListener#columnOrderChanged(int[])
	 */
	public void columnOrderChanged(int[] iPositions) {
		//TODO
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.TableStructureModificationListener#columnSizeChanged(com.aelitis.azureus.ui.common.table.TableColumnCore, int)
	 */
	public void columnSizeChanged(TableColumnCore tableColumn, int diff) {
		columnsWidth += diff;
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				if (cHeaderArea != null && !cHeaderArea.isDisposed()) {
					cHeaderArea.redraw();
				}
				swt_fixupSize();
				redrawTable();
			}
		});
		debug("columnsidth now " + columnsWidth);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#addKeyListener(org.eclipse.swt.events.KeyListener)
	 */
	public void addKeyListener(KeyListener listener) {
		if (tvSWTCommon == null) {
			return;
		}
		tvSWTCommon.addKeyListener(listener);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#removeKeyListener(org.eclipse.swt.events.KeyListener)
	 */
	public void removeKeyListener(KeyListener listener) {
		if (tvSWTCommon == null) {
			return;
		}
		tvSWTCommon.removeKeyListener(listener);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getKeyListeners()
	 */
	public KeyListener[] getKeyListeners() {
		if (tvSWTCommon == null) {
			return new KeyListener[0];
		}
		return tvSWTCommon.getKeyListeners();
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#addMenuFillListener(org.gudy.azureus2.ui.swt.views.table.TableViewSWTMenuFillListener)
	 */
	public void addMenuFillListener(TableViewSWTMenuFillListener l) {
		if (tvSWTCommon == null) {
			return;
		}
		tvSWTCommon.addMenuFillListener(l);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#createDragSource(int)
	 */
	public DragSource createDragSource(int style) {
		final DragSource dragSource = new DragSource(cTable, style);
		dragSource.addDragListener(new DragSourceAdapter() {
			public void dragStart(DragSourceEvent event) {
				cTable.setCursor(null);
				isDragging = true;
			}

			public void dragFinished(DragSourceEvent event) {
				isDragging = false;
			}
		});
		cTable.addDisposeListener(new DisposeListener() {
			// @see org.eclipse.swt.events.DisposeListener#widgetDisposed(org.eclipse.swt.events.DisposeEvent)
			public void widgetDisposed(DisposeEvent e) {
				if (!dragSource.isDisposed()) {
					dragSource.dispose();
				}
			}
		});
		return dragSource;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#createDropTarget(int)
	 */
	public DropTarget createDropTarget(int style) {
		final DropTarget dropTarget = new DropTarget(cTable, style);
		cTable.addDisposeListener(new DisposeListener() {
			// @see org.eclipse.swt.events.DisposeListener#widgetDisposed(org.eclipse.swt.events.DisposeEvent)
			public void widgetDisposed(DisposeEvent e) {
				if (!dropTarget.isDisposed()) {
					dropTarget.dispose();
				}
			}
		});
		return dropTarget;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getComposite()
	 */
	public Composite getComposite() {
		return cTable;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getRow(org.eclipse.swt.dnd.DropTargetEvent)
	 */
	public TableRowCore getRow(DropTargetEvent event) {
		//TODO
		// maybe
		Point pt = cTable.toControl(event.x, event.y);
		return getRow(pt.x, pt.y);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getRowSWT(java.lang.Object)
	 */
	public TableRowSWT getRowSWT(Object dataSource) {
		return (TableRowSWT) getRow(dataSource);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getTableComposite()
	 */
	public Composite getTableComposite() {
		return cTable;
	}

	/** Creates a composite within the specified composite and sets its layout
	 * to a default FillLayout().
	 *
	 * @param composite to create your Composite under
	 * @return The newly created composite
	 */
	public Composite createMainPanel(Composite composite) {
		TableViewSWTPanelCreator mainPanelCreator = getMainPanelCreator();
		if (mainPanelCreator != null) {
			return mainPanelCreator.createTableViewPanel(composite);
		}
		Composite panel = new Composite(composite, SWT.NO_FOCUS);
		composite.getLayout();
		GridLayout layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		panel.setLayout(layout);

		Object parentLayout = composite.getLayout();
		if (parentLayout == null || (parentLayout instanceof GridLayout)) {
			panel.setLayoutData(new GridData(GridData.FILL_BOTH));
		}

		return panel;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#initialize(org.eclipse.swt.widgets.Composite)
	 */
	public void initialize(Composite parent) {
		tvTabsCommon = new TableViewSWT_TabsCommon(this);

		shell = parent.getShell();
		mainComposite = tvTabsCommon.createSashForm(parent);
		Composite cTableComposite = tvTabsCommon.tableComposite;

		cTableComposite.setLayout(new FormLayout());
		Layout layout = parent.getLayout();
		if (layout instanceof FormLayout) {
			FormData fd = Utils.getFilledFormData();
			cTableComposite.setLayoutData(fd);
		}

		cHeaderArea = new Canvas(cTableComposite, SWT.NONE);

		sc = new ScrolledComposite(cTableComposite, SWT.H_SCROLL | SWT.V_SCROLL);
		sc.setExpandHorizontal(true);
		sc.setExpandVertical(true);

		cTable = new Canvas(sc, SWT.NO_BACKGROUND);
		sc.setContent(cTable);

		cTable.setBackground(parent.getDisplay().getSystemColor(
				SWT.COLOR_LIST_BACKGROUND));

		FormData fd = Utils.getFilledFormData();
		fd.height = headerHeight;
		fd.bottom = null;
		cHeaderArea.setLayoutData(fd);
		fd = Utils.getFilledFormData();
		fd.top = new FormAttachment(cHeaderArea);
		sc.setLayoutData(fd);

		clientArea = cTable.getClientArea();

		TableColumnCore[] tableColumns = getAllColumns();
		TableColumnCore[] tmpColumnsOrdered = new TableColumnCore[tableColumns.length];
		//Create all columns
		int columnOrderPos = 0;
		Arrays.sort(tableColumns,
				TableColumnManager.getTableColumnOrderComparator());
		for (int i = 0; i < tableColumns.length; i++) {
			int position = tableColumns[i].getPosition();
			if (position != -1 && tableColumns[i].isVisible()) {
				//table.createNewColumn(SWT.NULL);
				//System.out.println(i + "] " + tableColumns[i].getName() + ";" + position);
				tmpColumnsOrdered[columnOrderPos++] = tableColumns[i];
			}
		}
		//int numSWTColumns = table.getColumnCount();
		//int iNewLength = numSWTColumns - (bSkipFirstColumn ? 1 : 0);
		TableColumnCore[] columnsOrdered = new TableColumnCore[columnOrderPos];
		System.arraycopy(tmpColumnsOrdered, 0, columnsOrdered, 0, columnOrderPos);
		setColumnsOrdered(columnsOrdered);

		cTable.addPaintListener(new PaintListener() {
			public void paintControl(PaintEvent e) {
				//System.out.println("PAINT!");
				paintComposite(e);
			}
		});

		menu = createMenu();
		cTable.setMenu(menu);
		cHeaderArea.setMenu(menu);

		setupHeaderArea(cHeaderArea);

		cTable.addControlListener(new ControlListener() {
			private boolean callingCCA = false;
			
			public void controlResized(ControlEvent e) {
				swt_calculateClientArea();
			}
			
			public void controlMoved(ControlEvent e) {
				// paint will get called right after move, so we need to update
				// clientArea and visibleRow offsets NOW

				//System.out.println("Moved! " + cTable.getLocation() + ";" + cTable.getClientArea());
				swt_calculateClientArea();
			}
		});
		ScrollBar hBar = sc.getHorizontalBar();
		if (hBar != null) {
			hBar.setThumb(10);
			hBar.setIncrement(10);
		}
		ScrollBar vBar = sc.getVerticalBar();
		if (vBar != null) {
			vBar.setThumb(8);
			vBar.setIncrement(8);
		}


		cTable.addMouseListener(tvSWTCommon);
		cTable.addMouseMoveListener(tvSWTCommon);
		cTable.addKeyListener(tvSWTCommon);
		//composite.addSelectionListener(tvSWTCommon);

		new TableTooltips(this, cTable);

		tvTabsCommon = new TableViewSWT_TabsCommon(this);

		TableColumnManager tcManager = TableColumnManager.getInstance();

		String sSortColumn = tcManager.getDefaultSortColumnName(tableID);
		if (sSortColumn == null || sSortColumn.length() == 0) {
			sSortColumn = sDefaultSortOn;
		}

		TableColumnCore tc = tcManager.getTableColumnCore(tableID, sSortColumn);
		if (tc == null && tableColumns.length > 0) {
			tc = tableColumns[0];
		}
		setSortColumn(tc, false);

		triggerLifeCycleListener(TableLifeCycleListener.EVENT_INITIALIZED);

		configMan.addParameterListener("Graphics Update", this);
		configMan.addParameterListener("ReOrder Delay", this);
		Colors.getInstance().addColorsChangedListener(this);

		// So all TableView objects of the same TableID have the same columns,
		// and column widths, etc
		TableStructureEventDispatcher.getInstance(tableID).addListener(this);

	}

	private void setupHeaderArea(final Canvas cHeaderArea) {

		cHeaderArea.addPaintListener(new PaintListener() {
			public void paintControl(PaintEvent e) {
				paintHeader(e);
			}
		});

		Listener l = new Listener() {
			boolean mouseDown = false;

			TableColumnCore columnSizing;

			int columnSizingStart = 0;

			public void handleEvent(Event e) {
				switch (e.type) {
					case SWT.MouseDown: {
						mouseDown = true;

						columnSizing = null;
						int x = -clientArea.x;
						TableColumnCore[] visibleColumns = getVisibleColumns();
						for (TableColumnCore column : visibleColumns) {
							int w = column.getWidth();
							x += w;

							if (e.x >= x - 3 && e.x <= x + 3) {
								columnSizing = column;
								columnSizingStart = e.x;
								break;
							}
						}

						break;
					}

					case SWT.MouseUp: {
						if (mouseDown && columnSizing == null) {
							TableColumnCore column = getTableColumnByOffset(e.x);
							if (column != null) {
								setSortColumn(column, true);
							}
						}
						columnSizing = null;
						mouseDown = false;
						break;
					}

					case SWT.MouseMove: {
						if (columnSizing != null) {
							int diff = (e.x - columnSizingStart);
							columnSizing.setWidth(columnSizing.getWidth() + diff);
							columnSizingStart = e.x;
						} else {
							int cursorID = SWT.CURSOR_HAND;
							int x = -clientArea.x;
							TableColumnCore[] visibleColumns = getVisibleColumns();
							for (TableColumnCore column : visibleColumns) {
								int w = column.getWidth();
								x += w;

								if (e.x >= x - 3 && e.x <= x + 3) {
									cursorID = SWT.CURSOR_SIZEWE;
									break;
								}
							}
							cHeaderArea.setCursor(e.display.getSystemCursor(cursorID));
						}
					}

				}
			}
		};

		cHeaderArea.addListener(SWT.MouseDown, l);
		cHeaderArea.addListener(SWT.MouseUp, l);
		cHeaderArea.addListener(SWT.MouseMove, l);

		Transfer[] types = new Transfer[] {
			TextTransfer.getInstance()
		};

		final DragSource ds = new DragSource(cHeaderArea, DND.DROP_MOVE);
		ds.setTransfer(types);
		ds.addDragListener(new DragSourceListener() {
			private String eventData;

			public void dragStart(DragSourceEvent event) {
				Cursor cursor = cHeaderArea.getCursor();
				if (cursor != null
						&& cursor.equals(event.display.getSystemCursor(SWT.CURSOR_SIZEWE))) {
					event.doit = false;
					return;
				}

				cHeaderArea.setCursor(null);
				TableColumnCore tc = getTableColumnByOffset(event.x);
				isHeaderDragging = tc != null;
				if (isHeaderDragging) {
					eventData = tc.getName();
				}
				System.out.println("drag " + eventData);
			}

			public void dragSetData(DragSourceEvent event) {
				event.data = eventData;
			}

			public void dragFinished(DragSourceEvent event) {
				isHeaderDragging = false;
				eventData = null;
			}
		});

		final DropTarget dt = new DropTarget(cHeaderArea, DND.DROP_MOVE);
		dt.setTransfer(types);
		dt.addDropListener(new DropTargetListener() {

			public void dropAccept(DropTargetEvent event) {
			}

			public void drop(final DropTargetEvent event) {
				System.out.println("drop " + event.data);
				if (event.data instanceof String) {
					TableColumn tcOrig = getTableColumn((String) event.data);
					TableColumn tcDest = getTableColumnByOffset(event.x);
					if (tcDest == null) {
						TableColumnCore[] visibleColumns = getVisibleColumns();
						if (visibleColumns != null && visibleColumns.length > 0) {
							tcDest = visibleColumns[visibleColumns.length - 1];
						}
					}
					if (tcOrig != null && tcDest != null) {
						int destPos = tcDest.getPosition();
						int origPos = tcOrig.getPosition();
						final boolean moveRight = destPos > origPos;
						TableColumnCore[] visibleColumns = getVisibleColumns();
						((TableColumnCore) tcOrig).setPositionNoShift(destPos);

						//System.out.println("Move " + origPos + " Right? " + moveRight + " of " + destPos);
						Arrays.sort(visibleColumns, new Comparator<TableColumnCore>() {
							public int compare(TableColumnCore o1, TableColumnCore o2) {
								if (o1 == o2) {
									return 0;
								}
								int diff = o1.getPosition() - o2.getPosition();
								if (diff == 0) {
									int i = o1.getName().equals(event.data) ? -1 : 1;
									if (moveRight) {
										i *= -1;
									}
									return i;
								}
								return diff;
							}
						});

						for (int i = 0; i < visibleColumns.length; i++) {
							TableColumnCore tc = visibleColumns[i];
							tc.setPositionNoShift(i);
						}
						setColumnsOrdered(visibleColumns);

						TableStructureEventDispatcher.getInstance(tableID).tableStructureChanged(
								false, getDataSourceType());
					}
				}
			}

			public void dragOver(DropTargetEvent event) {
			}

			public void dragOperationChanged(DropTargetEvent event) {
			}

			public void dragLeave(DropTargetEvent event) {
			}

			public void dragEnter(DropTargetEvent event) {
			}
		});
		cHeaderArea.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				Utils.disposeSWTObjects(new Object[] {
					ds,
					dt
				});
			}
		});
	}

	@Override
	public void tableStructureChanged(boolean columnAddedOrRemoved,
			Class forPluginDataSourceType) {
		super.tableStructureChanged(columnAddedOrRemoved, forPluginDataSourceType);

		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				if (cHeaderArea != null && !cHeaderArea.isDisposed()) {
					cHeaderArea.redraw();
				}

				redrawTable();
			}
		});
	}

	protected void paintComposite(PaintEvent e) {
		swt_calculateClientArea();
		if (canvasImage == null) {
			return;
		}

		GC gc = new GC(canvasImage);
		Rectangle clipping = e.gc.getClipping();
		// our image is size to include full rows.  If we are painting to the bottom
		// of the client area, adjust clipping so we paint to the bottom of full
		// row
		if (clipping.x + clipping.width == cTable.getSize().x) {
			e.width = clipping.width = canvasImage.getBounds().width - clipping.x;
		}
		//		if (clipping.x == 0) {
		//			System.out.println("CLIP START " + clientArea.x);
		//			e.width += clientArea.x;
		//			clipping.width += clientArea.x;
		//		} else {
		clipping.x -= clientArea.x;
		e.x -= clientArea.x;
		e.width += clientArea.x;
		clipping.width += clientArea.x;

		e.y -= clientArea.y;
		clipping.y -= clientArea.y;
		e.height += clientArea.y;
		clipping.height += clientArea.y;
		//		}
		if (clipping.y + clipping.height == cTable.getSize().y) {
			e.height = clipping.height = canvasImage.getBounds().height - clipping.y;
		}
		
		gc.setClipping(clipping);
		gc.setBackground(e.gc.getBackground());
		GC oldGC = e.gc;
		e.gc = gc;
		_paintComposite(e);
		e.gc = oldGC;
		gc.dispose();

		e.gc.drawImage(canvasImage, 0, clientArea.y);
	}

	protected void _paintComposite(PaintEvent e) {
		int end = e.y + e.height;

		TableRowCore oldRow = null;
		int pos = -1;
		synchronized (visibleRows) {
			for (TableRowCore row : visibleRows) {
				TableRowPainted paintedRow = (TableRowPainted) row;
				if (pos == -1) {
					pos = row.getIndex();
				} else {
					pos++;
				}
				Point drawOffset = paintedRow.getDrawOffset();
				//debug("Paint " + e.x + "x" + e.y + " " + e.width + "x" + e.height + ".." + e.count + "; Row=" +row.getIndex() + ";clip=" + e.gc.getClipping() +";drawOffset=" + drawOffset);
				paintedRow.paintControl(e, 0, drawOffset.y - clientArea.y, pos);
				oldRow = row;
			}
		}
		/*
		TableRowCore row = getRow(e.x, e.y);
		if (row == null) {
			debug("Paint: No 1st row @ " + e.y);
		}
		//debug("Paint " + e.x + "x" + e.y + " " + e.width + "x" + e.height + ".." + e.count);
		TableRowCore oldRow = row;
		int lastIndex = -1;
		while ((row instanceof TableRowPainted) && isRowVisible(row)) {
			TableRowPainted paintedRow = (TableRowPainted) row;
			Point drawOffset = paintedRow.getDrawOffset();
			//debug("Paint " + e.x + "x" + e.y + " " + e.width + "x" + e.height + ".." + e.count + "; Row=" +row.getIndex() + ";clip=" + e.gc.getClipping() +";drawOffset=" + drawOffset);
			paintedRow.paintControl(e, 0, drawOffset.y);

			oldRow = row;
			lastIndex = row.getIndex();
			row = getRow(lastIndex + 1);
		}
		*/
		int h;
		int yDirty;
		if (oldRow == null) {
			yDirty = e.y;
			h = e.height;
		} else {
			yDirty = ((TableRowPainted) oldRow).getDrawOffset().y
					+ ((TableRowPainted) oldRow).getFullHeight();
			h = (e.y + e.height) - yDirty;
		}
		if (h > 0) {
			int rowHeight = getRowDefaultHeight();
			while (yDirty < end) {
				pos++;
				Color color = TableRowPainted.alternatingColors[pos % 2];
				if (color != null) {
					e.gc.setBackground(color);
				}
				if (color == null) {
					e.gc.setBackground(e.gc.getDevice().getSystemColor(
							SWT.COLOR_LIST_BACKGROUND));
				}
				e.gc.fillRectangle(e.x, yDirty, e.width, rowHeight);
				yDirty += rowHeight;
			}
		}

		if (colorLine == null) {
			colorLine = cTable.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
			HSLColor hslColor = new HSLColor();
			hslColor.initHSLbyRGB(colorLine.getRed(), colorLine.getGreen(),
					colorLine.getBlue());

			int lum = hslColor.getLuminence();
			if (lum > 127)
				lum -= 25;
			else
				lum += 40;
			hslColor.setLuminence(lum);

			colorLine = new Color(cTable.getDisplay(), hslColor.getRed(),
					hslColor.getGreen(), hslColor.getBlue());
		}

		e.gc.setAlpha(90);
		e.gc.setBackground(cTable.getDisplay().getSystemColor(
				SWT.COLOR_LIST_BACKGROUND));
		TableColumnCore[] visibleColumns = getVisibleColumns();
		int x = 0;
		for (TableColumnCore column : visibleColumns) {
			x += column.getWidth();

			// Vertical lines between columns
			e.gc.setForeground(colorLine);
			e.gc.drawLine(x - 1, e.y, x - 1, e.y + e.height);
		}
	}

	private void paintHeader(PaintEvent e) {

		Rectangle ca = cHeaderArea.getClientArea();
		Pattern pattern = new Pattern(e.display, 0, 0, 0, ca.height, Colors.white,
				Colors.grey);
		e.gc.setBackgroundPattern(pattern);
		e.gc.fillRectangle(ca);

		TableColumnCore[] visibleColumns = getVisibleColumns();
		GCStringPrinter sp;
		TableColumnCore sortColumn = getSortColumn();
		int x = -clientArea.x;
		for (TableColumnCore column : visibleColumns) {
			int w = column.getWidth();

			e.gc.setForeground(ColorCache.getColor(e.display, 0, 0, 0));
			if (column.equals(sortColumn)) {
				int middle = w / 2;
				int y1, y2;
				int arrowHalfW = 7;
				int arrowHeight = 8;
				if (column.isSortAscending()) {
					y2 = 3;
					y1 = y2 + arrowHeight;
				} else {
					y1 = 3;
					y2 = y1 + arrowHeight;
				}
				e.gc.setAntialias(SWT.ON);
				e.gc.setBackground(ColorCache.getColor(e.display, 0, 0, 0));
				e.gc.fillPolygon(new int[] {
					x + middle - arrowHalfW,
					y1,
					x + middle + arrowHalfW,
					y1,
					x + middle,
					y2
				});
			}

			sp = new GCStringPrinter(e.gc,
					MessageText.getString(column.getTitleLanguageKey()), new Rectangle(
							x + 2, 10, w - 4, headerHeight - 12), true, false, SWT.WRAP
							| SWT.CENTER);
			sp.calculateMetrics();
			if (sp.isWordCut()) {
				Font font = e.gc.getFont();
				if (font70pct == null) {
					font70pct = FontUtils.getFontPercentOf(font, 0.7f);
				}
				e.gc.setFont(font70pct);
				sp.printString();
				e.gc.setFont(font);
			} else {
				sp.printString();
			}

			x += w;

			e.gc.setForeground(colorLine);
			e.gc.drawLine(x - 1, e.y, x - 1, e.y + e.height);
		}

		columnsWidth = x + clientArea.x;

		pattern.dispose();
		e.gc.setBackgroundPattern(null);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#obfusticatedImage(org.eclipse.swt.graphics.Image)
	 */
	public Image obfusticatedImage(Image image) {
		//TODO

		UISWTViewCore view = tvTabsCommon == null ? null
				: tvTabsCommon.getActiveSubView();
		if (view instanceof ObfusticateImage) {
			try {
				((ObfusticateImage) view).obfusticatedImage(image);
			} catch (Exception e) {
				Debug.out("Obfuscating " + view, e);
			}
		}
		return image;
	}

	protected TableViewSWTPanelCreator getMainPanelCreator() {
		return mainPanelCreator;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#setMainPanelCreator(org.gudy.azureus2.ui.swt.views.table.TableViewSWTPanelCreator)
	 */
	public void setMainPanelCreator(TableViewSWTPanelCreator mainPanelCreator) {
		this.mainPanelCreator = mainPanelCreator;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#setRowDefaultIconSize(org.eclipse.swt.graphics.Point)
	 */
	public void setRowDefaultIconSize(Point size) {
		setRowDefaultHeight(size.y);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getTableCell(int, int)
	 */
	public TableCellCore getTableCell(int x, int y) {
		TableRowSWT row = getTableRow(x, y, true);
		if (row == null) {
			return null;
		}

		TableColumnCore column = getTableColumnByOffset(x);
		if (column == null) {
			return null;
		}

		return row.getTableCellCore(column.getName());
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getTableCellMouseOffset(org.gudy.azureus2.ui.swt.views.table.TableCellSWT)
	 */
	public Point getTableCellMouseOffset(TableCellSWT tableCell) {
		if (tableCell == null) {
			return null;
		}
		Point pt = cTable.getDisplay().getCursorLocation();
		pt = cTable.toControl(pt);

		Rectangle bounds = tableCell.getBounds();
		int x = pt.x - bounds.x;
		if (x < 0 || x > bounds.width) {
			return null;
		}
		int y = pt.y - bounds.y;
		if (y < 0 || y > bounds.height) {
			return null;
		}
		return new Point(x, y);
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#enableFilterCheck(org.eclipse.swt.widgets.Text, org.gudy.azureus2.ui.swt.views.table.TableViewFilterCheck)
	 */
	public void enableFilterCheck(Text txtFilter,
			TableViewFilterCheck<Object> filterCheck) {
		TableViewSWTFilter<?> filter = getSWTFilter();
		if (filter != null) {
			if (filter.widget != null && !filter.widget.isDisposed()) {
				filter.widget.removeKeyListener(tvSWTCommon);
				filter.widget.removeModifyListener(filter.widgetModifyListener);
			}
		} else {
			this.filter = filter = new TableViewSWTFilter();
		}
		filter.widget = txtFilter;
		if (txtFilter != null) {
			txtFilter.setMessage("Filter");
			txtFilter.addKeyListener(tvSWTCommon);

			filter.widgetModifyListener = new ModifyListener() {
				public void modifyText(ModifyEvent e) {
					setFilterText(((Text) e.widget).getText());
				}
			};
			txtFilter.addModifyListener(filter.widgetModifyListener);

			if (txtFilter.getText().length() == 0) {
				txtFilter.setText(filter.text);
			} else {
				filter.text = filter.nextText = txtFilter.getText();
			}
		} else {
			filter.text = filter.nextText = "";
		}

		filter.checker = filterCheck;

		filter.checker.filterSet(filter.text);
		refilter();
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#setFilterText(java.lang.String)
	 */
	public void setFilterText(String s) {
		if (tvSWTCommon != null) {
			tvSWTCommon.setFilterText(s);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#enableSizeSlider(org.eclipse.swt.widgets.Composite, int, int)
	 */
	public boolean enableSizeSlider(Composite composite, int min, int max) {
		// TODO
		return false;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#disableSizeSlider()
	 */
	public void disableSizeSlider() {
		// TODO
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#addRowPaintListener(org.gudy.azureus2.ui.swt.views.table.TableRowSWTPaintListener)
	 */
	public void addRowPaintListener(TableRowSWTPaintListener listener) {
		if (tvSWTCommon != null) {
			tvSWTCommon.addRowPaintListener(listener);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#removeRowPaintListener(org.gudy.azureus2.ui.swt.views.table.TableRowSWTPaintListener)
	 */
	public void removeRowPaintListener(TableRowSWTPaintListener listener) {
		if (tvSWTCommon != null) {
			tvSWTCommon.removeRowPaintListener(listener);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#invokePaintListeners(org.eclipse.swt.graphics.GC, com.aelitis.azureus.ui.common.table.TableRowCore, com.aelitis.azureus.ui.common.table.TableColumnCore, org.eclipse.swt.graphics.Rectangle)
	 */
	public void invokePaintListeners(GC gc, TableRowCore row,
			TableColumnCore column, Rectangle cellArea) {
		if (tvSWTCommon != null) {
			tvSWTCommon.invokePaintListeners(gc, row, column, cellArea);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#addRowMouseListener(org.gudy.azureus2.plugins.ui.tables.TableRowMouseListener)
	 */
	public void addRowMouseListener(TableRowMouseListener listener) {
		if (tvSWTCommon != null) {
			tvSWTCommon.addRowMouseListener(listener);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#removeRowMouseListener(org.gudy.azureus2.plugins.ui.tables.TableRowMouseListener)
	 */
	public void removeRowMouseListener(TableRowMouseListener listener) {
		if (tvSWTCommon != null) {
			tvSWTCommon.removeRowMouseListener(listener);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#invokeRowMouseListener(org.gudy.azureus2.plugins.ui.tables.TableRowMouseEvent)
	 */
	public void invokeRowMouseListener(TableRowMouseEvent event) {
		if (tvSWTCommon != null) {
			tvSWTCommon.invokeRowMouseListener(event);
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#getTableOrTreeSWT()
	 */
	public TableOrTreeSWT getTableOrTreeSWT() {
		return null;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.table.TableViewSWT#packColumns()
	 */
	public void packColumns() {
		// TODO
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.core3.config.ParameterListener#parameterChanged(java.lang.String)
	 */
	public void parameterChanged(String parameterName) {
		if (parameterName == null || parameterName.equals("Graphics Update")) {
			graphicsUpdate = configMan.getIntParameter("Graphics Update");
		}
		if (parameterName == null || parameterName.equals("ReOrder Delay")) {
			reOrderDelay = configMan.getIntParameter("ReOrder Delay");
		}
		if (parameterName == null || parameterName.startsWith("Color")) {
			tableInvalidate();
		}
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.impl.TableViewImpl#createNewRow(java.lang.Object)
	 */
	@Override
	public TableRowCore createNewRow(Object object) {
		return new TableRowPainted(null, this, object, true);
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.impl.TableViewImpl#visibleRowsChanged()
	 */
	@Override
	public void visibleRowsChanged() {
		swt_visibleRowsChanged();
	}

	private boolean visibleRowsFillClientArea() {
		return visibleRowsHeight >= clientArea.height;
	}

	private void swt_visibleRowsChanged() {
		final List<TableRowSWT> newlyVisibleRows = new ArrayList<TableRowSWT>();
		final List<TableRowSWT> nowInVisibleRows;
		final ArrayList<TableRowSWT> rowsStayedVisibleButMoved = new ArrayList<TableRowSWT>();
		synchronized (this) {
			List<TableRowSWT> newVisibleRows;
			if (isVisible()) {
				// this makes a copy.. slower
				TableRowCore[] rows = getRows();
				newVisibleRows = new ArrayList<TableRowSWT>();
				recalculateVisibleRows(rows, 0, newVisibleRows,
						rowsStayedVisibleButMoved);

			} else {
				newVisibleRows = Collections.emptyList();
			}
			synchronized (visibleRows) {
				nowInVisibleRows = new ArrayList<TableRowSWT>(0);
				if (visibleRows != null) {
					nowInVisibleRows.addAll(Arrays.asList(visibleRows));
				}
				TableRowPainted[] rows = new TableRowPainted[newVisibleRows.size()];
				int pos = 0;
				visibleRowsHeight = 0;
				for (TableRowSWT row : newVisibleRows) {
					rows[pos++] = (TableRowPainted) row;
					boolean removed = nowInVisibleRows.remove(row);
					if (!removed) {
						newlyVisibleRows.add(row);
					}
					visibleRowsHeight += row.getFullHeight();
				}

				visibleRows = rows;
			}
		}

		if (DEBUG_ROWCHANGE) {
			System.out.println("visRowsChanged; shown=" + visibleRows.length + "; +"
					+ newlyVisibleRows.size() + "/-" + nowInVisibleRows.size() + "/"
					+ rowsStayedVisibleButMoved.size() + " via "
					+ Debug.getCompressedStackTrace(8));
		}
		Utils.getOffOfSWTThread(new AERunnable() {

			public void runSupport() {
				boolean bTableUpdate = false;

				for (TableRowSWT row : newlyVisibleRows) {
					// no need to refres, the redraw will do it
					//row.refresh(true, true);
					row.setShown(true, false);
					row.invalidate();
					row.redraw();
					rowsStayedVisibleButMoved.remove(row);
					if (Constants.isOSX) {
						bTableUpdate = true;
					}
				}

				for (TableRowSWT row : rowsStayedVisibleButMoved) {
					row.invalidate();
					row.redraw();
				}

				for (TableRowSWT row : nowInVisibleRows) {
					row.setShown(false, false);
				}

				if (bTableUpdate) {
					Utils.execSWTThread(new AERunnable() {
						public void runSupport() {
							if (cTable != null && !cTable.isDisposed()) {
								cTable.update();
							}
						}
					});
				}

			}
		});
	}

	private void recalculateVisibleRows(TableRowCore[] rows, int yStart,
			List<TableRowSWT> newVisibleRows,
			List<TableRowSWT> rowsStayedVisibleButMoved) {
		Rectangle bounds = clientArea;

		int y = yStart;
		if (DEBUG_ROWCHANGE) {
			System.out.print("Visible Rows: ");
		}
		for (TableRowCore row : rows) {
			if (row == null) {
				continue;
			}
			TableRowPainted rowSWT = ((TableRowPainted) row);
			int rowHeight = rowSWT.getHeight();
			int rowFullHeight = rowSWT.getFullHeight();

			if ((y < bounds.y + bounds.height) && (y + rowFullHeight > bounds.y)) {
				// this row or subrows are visible

				boolean offsetChanged = rowSWT.setDrawOffset(new Point(bounds.x, y));

				// check if this row
				if (y + rowHeight > bounds.y) {
					if (DEBUG_ROWCHANGE) {
						System.out.print((rowSWT.getParentRowCore() == null ? ""
								: rowSWT.getParentRowCore().getIndex() + ".")
								+ rowSWT.getIndex()
								+ "(ofs=" + (offsetChanged ? "*" : "")
								+ y
								+ ";rh="
								+ rowHeight + "/" + rowFullHeight + ")" + ", ");
					}

					if (offsetChanged) {
						rowsStayedVisibleButMoved.add(rowSWT);
					}
					newVisibleRows.add(rowSWT);
				}

				// check if subrows
				if (row.isExpanded()) {
					TableRowCore[] subRowsWithNull = row.getSubRowsWithNull();
					if (subRowsWithNull.length > 0) {
						recalculateVisibleRows(subRowsWithNull, y + rowHeight,
								newVisibleRows, rowsStayedVisibleButMoved);
					}
				}
			} else if (newVisibleRows.size() > 0) {
				if (DEBUG_ROWCHANGE) {
					System.out.print("break(ofs=" + y + ";bounds=" + bounds + ";rh=" + rowFullHeight + ")");
				}
				break;
			}
			y += rowFullHeight;
		}
		if (DEBUG_ROWCHANGE && yStart == 0) {
			System.out.println();
		}
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.impl.TableViewImpl#uiGetTopIndex()
	 */
	@Override
	public int uiGetTopIndex() {
		synchronized (visibleRows) {
			if (visibleRows == null || visibleRows.length == 0) {
				return 0;
			}
			return indexOf(visibleRows[0]);
		}
	}

	/* (non-Javadoc)
	 * @see com.aelitis.azureus.ui.common.table.impl.TableViewImpl#uiGetBottomIndex(int)
	 */
	@Override
	public int uiGetBottomIndex(int iTopIndex) {
		synchronized (visibleRows) {
			if (visibleRows == null || visibleRows.length == 0) {
				return getRowCount() - 1;
			}
			return indexOf(visibleRows[visibleRows.length - 1]);
		}
	}

	@Override
	public void uiRemoveRows(TableRowCore[] rows, Integer[] rowIndexes) {
		// TODO
		
		if (focusedRow != null) {
  		for (TableRowCore row : rows) {
  			if (row == focusedRow) {
  				setFocusedRow(null);
  				break;
  			}
  		}
  	}
	}


	@Override
	public void getOffUIThread(AERunnable runnable) {
		Utils.getOffOfSWTThread(runnable);
	}

	protected void swt_calculateClientArea() {
		if (cTable.isDisposed()) {
			return;
		}
		Rectangle oldClientArea = clientArea;
		Rectangle newClientArea = sc.getClientArea();
		Point location = cTable.getLocation();
		newClientArea.x = -location.x;
		newClientArea.y = -location.y;

		boolean clientAreaCausedVisibilityChanged = false;

		int w = 0;
		TableColumnCore[] visibleColumns = getVisibleColumns();
		for (TableColumnCore column : visibleColumns) {
			w += column.getWidth();
		}
		columnsWidth = w;
		newClientArea.width = Math.max(newClientArea.width, w);

		boolean changedX;
		boolean changedY;
		boolean changedW;
		boolean changedH;
		if (oldClientArea != null) {
			changedX = oldClientArea.x != newClientArea.x;
			changedY = oldClientArea.y != newClientArea.y;
			changedW = oldClientArea.width != newClientArea.width;
			changedH = oldClientArea.height != newClientArea.height;
		} else {
			changedX = changedY = changedW = changedH = true;
		}
		
		clientArea = newClientArea;
		
		//System.out.println("CA=" + clientArea + " via " + Debug.getCompressedStackTrace());

		if (changedX || changedW) {
			clientAreaCausedVisibilityChanged = true;
		}
		if (changedY || changedH) {
			if (changedY && oldClientArea != null) {
				if (visibleRows.length > 0) {
					if (oldClientArea.y > newClientArea.y && visibleRows[0].getDrawOffset().y < oldClientArea.y) {
  					visibleRows[0].invalidate();
  					visibleRows[0].clearCellFlag(TableCellSWTBase.FLAG_PAINTED, false);
					} else {
						TableRowPainted row = visibleRows[visibleRows.length - 1];
						int bottom = row.getDrawOffset().y + row.getHeight();
						if (bottom > oldClientArea.y + oldClientArea.height) {
    					row.invalidate();
    					row.clearCellFlag(TableCellSWTBase.FLAG_PAINTED, false);
						}
					}
				}
			}
			visibleRowsChanged();
		}

		if (changedX) {
			cHeaderArea.redraw();
			cTable.redraw();
		}

		Image newImage = canvasImage;

		//List<TableRowSWT> visibleRows = getVisibleRows();
		int h = 0;
		synchronized (visibleRows) {
			if (visibleRows.length > 0) {
				TableRowPainted lastRow = visibleRows[visibleRows.length - 1];
				h = lastRow.getDrawOffset().y + lastRow.getHeight();
				if (h < clientArea.height && lastRow.isExpanded()) {
					TableRowCore[] subRows = lastRow.getSubRowsWithNull();
					for (TableRowCore subRow : subRows) {
						if (subRow == null) {
							continue;
						}
						TableRowPainted subRowP = (TableRowPainted) subRow;

						h += subRowP.getFullHeight();
						if (h >= clientArea.height) {
							break;
						}
					}
				}
			}
		}
		if (h < clientArea.height) {
			h = clientArea.height;
		}

		//			 if (oldClientArea == null || oldClientArea.width != clientArea.width			|| oldClientArea.height != clientArea.height) {

		int oldH = canvasImage == null || canvasImage.isDisposed() ? 0
				: canvasImage.getBounds().height;
		int oldW = canvasImage == null || canvasImage.isDisposed() ? 0
				: canvasImage.getBounds().width;

		if (canvasImage != null) {
			if (oldClientArea != null) {
				if (canvasImage.isDisposed() || (clientArea.width != oldW || h != oldH)) {
					newImage = new Image(shell.getDisplay(), clientArea.width, h);
				}
				Rectangle imgBounds = new Rectangle(0, 0, clientArea.width, h);
				int moveToY = oldClientArea.y - clientArea.y;
				//Rectangle oldImageBounds = oldImg.getBounds();
				//gc.fillRectangle(imgBounds);
				if (moveToY != 0 || newImage != canvasImage) {
					GC gc = new GC(newImage);

					gc.drawImage(canvasImage, 0, moveToY);
					
					/*
					gc.setBackground(ColorCache.getRandomColor());
					if (moveToY < 0) {
						int y = imgBounds.height + moveToY;
						gc.fillRectangle(0, y, clientArea.width, -moveToY);
					} else if (moveToY > 0) {
						gc.fillRectangle(0, 0, clientArea.width, moveToY);
					}
					*/

					gc.dispose();

					if (!Constants.isOSX) {
						// OSX has translucent scrollbars.  If we try to paint over them with
						// GC(composite), it fails.  We could do the following code AND
						//	composite.redraw(clientArea.width - 20, 0, 20, clientArea.height, true);
						// but then there's a lag between our gc paint and the redraw's paint
						gc = new GC(cTable);
						gc.drawImage(canvasImage, 0, moveToY);
						gc.dispose();

						if (moveToY < 0) {
							// redraw bottom
							int y = imgBounds.height + moveToY;
							cTable.redraw(0, y, clientArea.width, -moveToY, true);
						} else if (moveToY > 0) {
							// redraw top
							cTable.redraw(0, 0, clientArea.width, moveToY, true);
						}
					} else {
						cTable.redraw();
					}
				}
			}
		} else {
			if (h <= 0 || clientArea.width <= 0) {
				newImage = null;
			} else {
				newImage = new Image(shell.getDisplay(), clientArea.width, h);
			}
		}
		if (canvasImage != newImage) {
			Image oldImage = canvasImage;
			canvasImage = newImage;
			if (oldImage != null && !oldImage.isDisposed()) {
				oldImage.dispose();
			}
		}

		//		System.out.println("imgBounds = " + canvasImage.getBounds() + ";ca="
		//				+ clientArea + ";" + composite.getClientArea() + ";h=" + h + ";oh="
		//				+ oldH + " via " + Debug.getCompressedStackTrace(3));

		if (clientAreaCausedVisibilityChanged) {
			columnVisibilitiesChanged = true;
			Utils.execSWTThreadLater(50, new AERunnable() {
				public void runSupport() {
					if (columnVisibilitiesChanged) {
						refreshTable(false);
					}
				}
			});
		}
	}

	public Rectangle getClientArea() {
		return clientArea;
	}

	public boolean isVisible() {
		if (!Utils.isThisThreadSWT()) {
			return isVisible;
		}
		boolean wasVisible = isVisible;
		isVisible = cTable != null && !cTable.isDisposed() && cTable.isVisible()
				&& !shell.getMinimized();
		if (isVisible != wasVisible) {
			visibleRowsChanged();
			UISWTViewCore view = tvTabsCommon == null ? null
					: tvTabsCommon.getActiveSubView();
			if (isVisible) {
				loopFactor = 0;

				if (view != null) {
					view.triggerEvent(UISWTViewEvent.TYPE_FOCUSGAINED, null);
				}
			} else {
				if (view != null) {
					view.triggerEvent(UISWTViewEvent.TYPE_FOCUSLOST, null);
				}
			}
		}
		return isVisible;
	}

	public void removeAllTableRows() {
		super.removeAllTableRows();
		visibleRows = new TableRowPainted[0];
		totalHeight = 0;
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				swt_fixupSize();
				if (cTable != null && !cTable.isDisposed()) {
					cTable.redraw();
				}
			}
		});
	}

	protected void swt_fixupSize() {
		// TODO
		System.out.println("Set minSize to " + columnsWidth + "x" + totalHeight);
		sc.setMinSize(columnsWidth, totalHeight);
	}

	@Override
	protected void uiChangeColumnIndicator() {
		Utils.execSWTThread(new AERunnable() {

			@Override
			public void runSupport() {
				if (cHeaderArea != null && !cHeaderArea.isDisposed()) {
					cHeaderArea.redraw();
				}
			}
		});
	}

	public TableColumnCore getTableColumnByOffset(int mouseX) {
		int x = -clientArea.x;
		TableColumnCore[] visibleColumns = getVisibleColumns();
		for (TableColumnCore column : visibleColumns) {
			int w = column.getWidth();

			if (mouseX >= x && mouseX < x + w) {
				return column;
			}

			x += w;
		}
		return null;
	}

	public TableRowSWT getTableRow(int x, int y, boolean anyX) {
		return (TableRowSWT) getRow(anyX ? 2 : x, y);
	}

	@Override
	public void setSelectedRows(TableRowCore[] newSelectionArray, boolean trigger) {
		super.setSelectedRows(newSelectionArray, trigger);

		setFocusedRow(newSelectionArray.length == 0 ? null : newSelectionArray[0]);
	}

	public void setRowSelected(final TableRowCore row, boolean selected,
			boolean trigger) {
		if (selected && !isSelected(row)) {
			setFocusedRow(row);
		}
		super.setRowSelected(row, selected, trigger);

		if (row instanceof TableRowSWT) {
			((TableRowSWT) row).setWidgetSelected(selected);
		}
	}

	public void editCell(int column, int row) {
		//TODO
	}

	public int getColumnNo(int mouseX) {
		int x = -clientArea.x;
		TableColumnCore[] visibleColumns = getVisibleColumns();
		for (int i = 0; i < visibleColumns.length; i++) {
			TableColumnCore column = visibleColumns[i];
			int w = column.getWidth();

			if (mouseX >= x && mouseX < x + w) {
				return i;
			}

			x += w;
		}
		return -1;
	}

	public boolean isDragging() {
		return isDragging;
	}

	public TableViewSWTFilter<?> getSWTFilter() {
		return (TableViewSWTFilter<?>) filter;
	}

	public void openFilterDialog() {
		if (filter == null) {
			return;
		}
		SimpleTextEntryWindow entryWindow = new SimpleTextEntryWindow();
		entryWindow.initTexts("MyTorrentsView.dialog.setFilter.title", null,
				"MyTorrentsView.dialog.setFilter.text", new String[] {
					MessageText.getString(getTableID() + "View" + ".header")
				});
		entryWindow.setPreenteredText(filter.text, false);
		entryWindow.prompt();
		if (!entryWindow.hasSubmittedInput()) {
			return;
		}
		String message = entryWindow.getSubmittedInput();

		if (message == null) {
			message = "";
		}

		setFilterText(message);
	}

	public boolean isSingleSelection() {
		return !isMultiSelect;
	}

	public void expandColumns() {
		//TODO
	}

	@Override
	public void triggerTabViewsDataSourceChanged(boolean sendParent) {
		if (tvTabsCommon != null) {
			tvTabsCommon.triggerTabViewsDataSourceChanged(sendParent);
		}
	}

	@Override
	public void uiSelectionChanged(TableRowCore[] newlySelectedRows,
			TableRowCore[] deselectedRows) {
		synchronized (visibleRows) {
			if (visibleRows != null) {
				for (TableRowPainted row : visibleRows) {
					row.clearCellFlag(TableCellSWTBase.FLAG_PAINTED, false);
				}
			}
		}
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				cTable.redraw();
			}
		});
	}

	public void delete() {
		triggerLifeCycleListener(TableLifeCycleListener.EVENT_DESTROYED);

		if (tvTabsCommon != null) {
			tvTabsCommon.delete();
			tvTabsCommon = null;
		}

		TableStructureEventDispatcher.getInstance(tableID).removeListener(this);
		TableColumnManager tcManager = TableColumnManager.getInstance();
		if (tcManager != null) {
			tcManager.saveTableColumns(getDataSourceType(), tableID);
		}

		Utils.disposeSWTObjects(new Object[] {
			cTable,
			font70pct
		});
		cTable = null;
		font70pct = null;

		removeAllTableRows();
		configMan.removeParameterListener("ReOrder Delay", this);
		configMan.removeParameterListener("Graphics Update", this);
		Colors.getInstance().removeColorsChangedListener(this);

		super.delete();

		MessageText.removeListener(this);
	}

	@Override
	public void generate(IndentWriter writer) {
		super.generate(writer);

		if (tvTabsCommon != null) {
			tvTabsCommon.generate(writer);
		}
	}

	private Menu createMenu() {
		if (!isMenuEnabled()) {
			return null;
		}

		final Menu menu = new Menu(shell, SWT.POP_UP);
		cTable.addListener(SWT.MenuDetect, new Listener() {
			public void handleEvent(Event event) {
				if (event.widget == cHeaderArea) {
					menu.setData("inBlankArea", false);
					menu.setData("isHeader", true);

				} else {
					boolean noRow = getTableRowWithCursor() == null;

					menu.setData("inBlankArea", noRow);
					menu.setData("isHeader", false);
				}
				menu.setData("column", getTableColumnByOffset(event.x));
			}
		});
		cHeaderArea.addListener(SWT.MenuDetect, new Listener() {
			public void handleEvent(Event event) {
				menu.setData("inBlankArea", false);
				menu.setData("isHeader", true);
				menu.setData("column", getTableColumnByOffset(event.x));
			}
		});
		MenuBuildUtils.addMaintenanceListenerForMenu(menu,
				new MenuBuildUtils.MenuBuilder() {
					public void buildMenu(Menu menu, MenuEvent menuEvent) {
						Object oIsHeader = menu.getData("isHeader");
						boolean isHeader = (oIsHeader instanceof Boolean)
								? ((Boolean) oIsHeader).booleanValue() : false;

						Object oInBlankArea = menu.getData("inBlankArea");
						boolean inBlankArea = (oInBlankArea instanceof Boolean)
								? ((Boolean) oInBlankArea).booleanValue() : false;

						TableColumnCore column = (TableColumnCore) menu.getData("column");

						if (isHeader) {
							tvSWTCommon.fillColumnMenu(menu, column, false);
						} else if (inBlankArea) {
							tvSWTCommon.fillColumnMenu(menu, column, true);
						} else {
							tvSWTCommon.fillMenu(menu, column);
						}

					}
				});

		return menu;
	}

	public void showColumnEditor() {
		if (tvSWTCommon != null) {
			tvSWTCommon.showColumnEditor();
		}
	}

	@Override
	public TableRowCore getFocusedRow() {
		return focusedRow;
	}

	public void setFocusedRow(TableRowCore row) {
		if (focusedRow != null) {
			focusedRow.redraw(false);
		}
		if (!(row instanceof TableRowPainted)) {
			row = null;
		}
		focusedRow = (TableRowPainted) row;
		if (focusedRow != null) {
			if (focusedRow.isVisible()
					&& focusedRow.getDrawOffset().y + focusedRow.getHeight() <= clientArea.y + clientArea.height
					&& focusedRow.getDrawOffset().y >= clientArea.y) {
				// redraw for BG color change
				focusedRow.redraw(false);
			} else {

				showRow(focusedRow);
			}
		}
	}

	public void showRow(final TableRowCore rowToShow) {
		// scrollto
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				if (isDisposed()) {
					return;
				}
				
				if (rowToShow.isVisible()) {
					// draw offset is valid, use that to scroll
					int y = ((TableRowPainted) rowToShow).getDrawOffset().y;
					if (y + rowToShow.getHeight() > clientArea.y + clientArea.height) {
						y -= (clientArea.height - rowToShow.getHeight());
					}
					sc.setOrigin(clientArea.x, y);
				} else {
					TableRowCore parentFocusedRow = rowToShow;
					while (parentFocusedRow.getParentRowCore() != null) {
						parentFocusedRow = parentFocusedRow.getParentRowCore();
					}
					TableRowCore[] rows = getRows();
					int y = 0;
					for (TableRowCore row : rows) {
						if (row == parentFocusedRow) {
							if (parentFocusedRow != rowToShow) {
								y += row.getHeight();
								TableRowCore[] subRowsWithNull = parentFocusedRow.getSubRowsWithNull();
								for (TableRowCore subrow : subRowsWithNull) {
									if (subrow == rowToShow) {
										break;
									}
									y += ((TableRowPainted) subrow).getFullHeight();
								}
							}
							break;
						}
						y += ((TableRowPainted) row).getFullHeight();
					}

					if (y + rowToShow.getHeight() > clientArea.y + clientArea.height) {
						y -= (clientArea.height - rowToShow.getHeight());
					}
					// y now at top of focused row
					sc.setOrigin(clientArea.x, y);
				}
			}
		});
	}

	boolean qdRowHeightChanged = false;
	public void rowHeightChanged(final TableRowCore row, int oldHeight,
			int newHeight) {

		synchronized (this) {
  		totalHeight += (newHeight - oldHeight);
  		System.out.println("Height delta: " + (newHeight - oldHeight) + ";ttl=" + totalHeight);
  		if (isRowVisible(row)) {
  			visibleRowsHeight += (newHeight - oldHeight);
  		}
  
  		// TODO: Shouldn't we do visibleRowsChanged();
  
  		if (qdRowHeightChanged) {
  			return;
  		}
  		qdRowHeightChanged = true;
		}
		Utils.execSWTThread(new AERunnable() {
			public void runSupport() {
				synchronized (TableViewPainted.this) {
					qdRowHeightChanged = false;
				}
				swt_fixupSize();

				if (isRowVisible(row)) {
					redrawTable();
				}
			}
		});
	}

	public void redrawTable() {
		synchronized (TableViewPainted.this) {
			if (redrawTableScheduled) {
				return;
			}
			redrawTableScheduled = true;
		}

		Utils.execSWTThreadLater(0, new AERunnable() {
			public void runSupport() {
				synchronized (TableViewPainted.this) {
					redrawTableScheduled = false;
				}
				visibleRowsChanged();
				synchronized (visibleRows) {
					if (visibleRows != null) {
						for (TableRowSWT row : visibleRows) {
							((TableRowPainted) row).clearCellFlag(
									TableCellSWTBase.FLAG_PAINTED, true);
						}
					}
				}

				if (canvasImage != null && !canvasImage.isDisposed()) {
					canvasImage.dispose();
					canvasImage = null;
				}
				if (cTable != null && !cTable.isDisposed()) {
					cTable.redraw();
				}
			}
		});
	}
	
	private String prettyIndex(TableRowCore row) {
		String s = "" + row.getIndex();
		if (row.getParentRowCore() != null) {
			s = row.getParentRowCore().getIndex() + "." + s;
		}
		return s;
	}

	public void redrawRow(final TableRowPainted row) {
		if (row == null) {
			return;
		}
		if (TableRowPainted.DEBUG_ROW_PAINT) {
			System.out.println(SystemTime.getCurrentTime() + "} redraw "
					+ prettyIndex(row) + " scheduled via " + Debug.getCompressedStackTrace());
		}
		Utils.execSWTThread(new AERunnable() {

			public void runSupport() {
				if (!isVisible || !row.isVisible()
						|| row.doesAnyCellHaveFlag(TableCellSWTBase.FLAG_PAINTED)) {
					return;
				}
				Rectangle bounds = row.getDrawBounds();
				if (TableRowPainted.DEBUG_ROW_PAINT) {
					System.out.println(SystemTime.getCurrentTime() + "] redraw "
							+ prettyIndex(row) + " @ " + bounds);
				}
				if (bounds != null) {
					Composite composite = getComposite();
					if (composite != null && !composite.isDisposed()) {
						int h = isLastRow(row) ? composite.getSize().y - bounds.y
								: bounds.height;
						composite.redraw(bounds.x, bounds.y, bounds.width, h, false);
					}
				}
			}
		});
	}
}
