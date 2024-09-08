import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Enumeration;
import java.util.EventObject;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;

public class FTable extends JTable {
	private boolean hasRowModel;

	private Listener listener;

	public FTable(TableModel data) {
		super(data);

		ToolTipManager.sharedInstance().unregisterComponent(this);
	}

	public FTable(TableModel data, TableColumnModel columns) {
		super(data, columns);

		ToolTipManager.sharedInstance().unregisterComponent(this);
	}

	protected void configureEnclosingScrollPane() {
		if (getTableHeader() != null) super.configureEnclosingScrollPane();
	}

	protected void unconfigureEnclosingScrollPane() {
		if (getTableHeader() != null) super.unconfigureEnclosingScrollPane();
	}

	public void setTableHeader(JTableHeader h) {
		unconfigureEnclosingScrollPane();

		super.setTableHeader(h);

		configureEnclosingScrollPane();
	}

	public void changeSelection(int row, int column, boolean toggle, boolean extend) {
		ListSelectionModel rows = getSelectionModel();
		ListSelectionModel columns = getColumnModel().getSelectionModel();

		boolean selected = isCellSelected(row, column);

		int anchorRow = rows.getAnchorSelectionIndex(), anchorColumn = columns.getAnchorSelectionIndex();

		// This is done wrongly even in 1.4
		boolean anchorSelected = anchorRow != -1 && anchorColumn != -1 && isCellSelected(anchorRow, anchorColumn);

		TableColumnModels.change(columns, column, toggle, extend, selected, anchorSelected);
		TableColumnModels.change(rows, row, toggle, extend, selected, anchorSelected);

		// if you like it (I don't) ...
		scrollRectToVisible(getCellRect(row, column, true));
	}

	public void setColumnModel(TableColumnModel c) {
		if (c == null) throw new IllegalArgumentException("null");

		if (c == columnModel) return;

		finishEditing();

		if (columnModel != null) columnModel.removeColumnModelListener(listener());

		super.setColumnModel(c);

		columnModel.removeColumnModelListener(this);
		columnModel.addColumnModelListener(listener());
	}

	public void setSelectionModel(ListSelectionModel s) {
		if (s == null) throw new IllegalArgumentException("null");

		if (s == selectionModel) return;

		if (selectionModel != null) selectionModel.removeListSelectionListener(listener());

		super.setSelectionModel(s);

		selectionModel.removeListSelectionListener(this);
		selectionModel.addListSelectionListener(listener());
	}

	// super.setModel will call tableChanged()
	// and the editor will be removed there.
	public void setModel(TableModel data) {
		if (data == null) throw new IllegalArgumentException("null");

		if (data == dataModel) return;

		if (dataModel != null) dataModel.removeTableModelListener(listener());

		super.setModel(data);

		dataModel.removeTableModelListener(this);
		dataModel.addTableModelListener(listener());
	}

	public void setRowHeight(int height) {
		hasRowModel = false;

		super.setRowHeight(height);
	}

	public void setRowHeight(int row, int height) {
		hasRowModel = true;

		super.setRowHeight(row, height);
	}

	public boolean getScrollableTracksViewportHeight() {
		return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
	}

	public boolean getScrollableTracksViewportWidth() {
		return getAutoResizeMode() != AUTO_RESIZE_OFF || (getParent() instanceof JViewport && getParent().getWidth() > getPreferredSize().width);
	}

	protected TableColumn resizingColumn() {
		return getTableHeader() != null ? getTableHeader().getResizingColumn() : null;
	}

	// <1.4: choked with no rows or no columns
	// 1.4beta: removed editor unnecessarily, wrong behaviour
	// with no rows/column (#4466930)
	// should retain anchor cell?
	public void selectAll() {
		if (getRowCount() > 0) setRowSelectionInterval(0, getRowCount() - 1);

		if (getColumnCount() > 0) setColumnSelectionInterval(0, getColumnCount() - 1);
	}

	public void columnAdded(TableColumnModelEvent e) {
		int index = e.getToIndex();

		if (editingColumn != -1 && index <= editingColumn) setEditingColumn(editingColumn + 1);

		resizeAndRepaint();
	}

	public void columnRemoved(TableColumnModelEvent e) {
		if (editingColumn != -1) {
			int from = e.getFromIndex();

			if (editingColumn == from) {
				// The event doesn't carry the TableColumn,
				// so we cannot not the model index to store the
				// value into.
				// If the model index were known, could call
				// manually set the value (but not call finishEditing).
				removeEditor();
			} else if (editingColumn > from) setEditingColumn(editingColumn - 1);
		}

		resizeAndRepaint();
	}

	public void columnMoved(TableColumnModelEvent e) {
		if (editingColumn != -1) {
			int from = e.getFromIndex(), to = e.getToIndex();

			// Need to adjust editing column first. The value may
			// stored there below.

			if (editingColumn == from) setEditingColumn(to);
			else if (from > to) {
				if (editingColumn >= to && editingColumn < from) setEditingColumn(editingColumn + 1);
			} else {
				if (editingColumn > from && editingColumn <= to) setEditingColumn(editingColumn - 1);
			}

			TableColumn c = tableHeader != null ? tableHeader.getDraggedColumn() : null;

			// remove the editor because it appears on top of the moved column
			// if it is not contained there
			// As JTable cannot know whether the UI does that, the editor must
			// be removed here.

			// One could wait until the dragged column actually overlapped
			// the editing column, but that is more like magic and also
			// has overhead.

			// Actually, the editor could be restored when the drag has
			// finished.
			// That still doesn't solve the problem that invalid contents
			// (i.e. those that do not pass stopCellEditing()) will be lost.
			// so it is of limited usefulness.

			if (c != null && tableHeader.getDraggedDistance() != 0 && editingColumn != from) finishEditing();

		}

		repaint();
	}

	// Fix for rtl width assumption.
	public Rectangle getCellRect(int row, int column, boolean includeSpacing) {
		Rectangle result = super.getCellRect(row, column, includeSpacing);

		if (!getComponentOrientation().isLeftToRight() && column >= 0 && column < getColumnCount()) {
			TableColumnModel columns = getColumnModel();

			result.width = columns.getColumn(column).getWidth();

			result.x = getWidth() - result.width;

			for (int i = 0; i < column; i++)
				result.x -= columns.getColumn(i).getWidth();

			if (!includeSpacing && row >= 0 && row < getRowCount()) {
				int margin = getColumnModel().getColumnMargin();

				result.x -= margin / 2;
				result.width -= margin;
			}
		}

		return result;
	}

	// This is similar to the strategy of 1.4. Unlike prior resize strategies
	// it actually works when the columns have minimum/maximums sizes set.
	// But it will always layout twice (doLayout() will call setWidth() on
	// the columns, typically these have changed, then columnMarginChanged
	// will be called-back again, which will revalidate again. Hopefully
	// the second time no widths are changed anymore.
	public void columnMarginChanged(ChangeEvent e) {
		TableColumn c = resizingColumn();

		if (c != null && autoResizeMode == AUTO_RESIZE_OFF) c.setPreferredWidth(c.getWidth());

		resizeAndRepaint();
	}

	// See column margin changed. I see no reason to make sizeColumnsToFit()
	// with a argument different from -1 obsolete - it is the only way to
	// resize columns if one does not use the standard JTableHeader.

	// AUTO_RESIZE_OFF will use the preferred widths *absolutely*
	// and not scale them if the width happens to be different.
	public void doLayout() {
		if (getAutoResizeMode() == AUTO_RESIZE_OFF) {
			for (Enumeration e = getColumnModel().getColumns(); e.hasMoreElements();) {
				TableColumn d = (TableColumn) e.nextElement();

				if (d.getWidth() != d.getPreferredWidth()) d.setWidth(d.getPreferredWidth());
			}

			return;
		}

		TableColumn c = resizingColumn();

		if (c == null) {
			super.doLayout();
		} else {
			int column = TableColumnModels.indexOf(getColumnModel(), c);

			sizeColumnsToFit(column);

			/**
			 * This is what 1.4 does.
			 * May this infinite loop?
			 */

			int delta = getWidth() - getColumnModel().getTotalColumnWidth();

			if (delta != 0) c.setWidth(c.getWidth() + delta);
		}
	}

	public void tableChanged(TableModelEvent e) {
		int first = e.getFirstRow(), last = e.getLastRow();

		if (e.getType() == TableModelEvent.UPDATE) {
			if (first == TableModelEvent.HEADER_ROW || (first == 0 && last == Integer.MAX_VALUE)) {
				removeEditor();

				super.tableChanged(e);

				// Fix for 1.3 broken variable row heights
				// rowHeight may be 0 because this is called from the
				// constructor.
				if (getRowHeight() != 0) setRowHeight(getRowHeight());
			}

			else {
				int column = e.getColumn();

				if (column == TableModelEvent.ALL_COLUMNS) {
					if (editingRow != -1) {
						if (editingRow >= first && editingRow <= last) removeEditor();
					}

					Rectangle r = getCellRect(first, -1, false);

					if (last != first) r = r.union(getCellRect(last, -1, false));

					r.width = getWidth();

					repaint(r);
				} else {
					column = convertColumnIndexToView(column);

					if (column != -1) {
						if (editingColumn == column) {
							if (editingRow >= first && editingRow <= last) removeEditor();
						}

						Rectangle r = getCellRect(first, column, false);

						if (last != first) r = r.union(getCellRect(last, column, false));

						repaint(r);
					}
				}
			}
		} else if (e.getType() == TableModelEvent.INSERT) {
			// Will remove editor in 1.4 beta 1
			super.tableChanged(e);

			if (editingRow != -1) {
				if (editingRow >= first) setEditingRow(editingRow + 1 + last - first);
			}
		} else {
			// Will remove editor in 1.4 beta 1
			super.tableChanged(e);

			if (editingRow != -1) {
				if (editingRow >= first) if (editingRow <= last) removeEditor();
				else setEditingRow(editingRow - 1 + last - first);
			}
		}
	}

	// Fix for cancelCellEditing not being called
	// from removeEditor().

	// This makes assumptions about what the superclass does.
	// (But what is there more to do?).
	// Need to replace removeEditor() by super.removeEditor().

	public void editingStopped(ChangeEvent e) {
		TableCellEditor f = getCellEditor();

		if (f != null) {
			// JTable implementation is the other way around:
			// setValueAt before removeEditor.
			// That way, removeEditor() would be called from
			// tableChanged() again and try to cancel editing while
			// the editor has already stopped and is the process of
			// distributing the events.

			// these change by removeEditor()
			int row = editingRow, column = editingColumn;

			originalRemoveEditor();

			setValueAt(f.getCellEditorValue(), row, column);
		}
	}

	/**
	 * If cell editing is in progress: try to stop it, if that fails,
	 * cancel it. When this method returns, the JTable isn't editing.
	 */
	public void finishEditing() {
		TableCellEditor e = getCellEditor();

		if (e != null) if (!e.stopCellEditing()) e.cancelCellEditing();
	}

	// While at it, just as well provide these two methods.

	/**
	 * If cell editing is in progress: try to stop it.
	 * 
	 * @returns true iff the JTable isn't editing (anymore).
	 */
	public boolean stopEditing() {
		TableCellEditor e = getCellEditor();

		return e == null || e.stopCellEditing();
	}

	/**
	 * If cell editing is in progress: cancel it.
	 * When this method returns, the JTable isn't editing.
	 */
	// This now does the same as removeEditor(), but that has a less
	// obvious method name.
	public void cancelEditing() {
		TableCellEditor e = getCellEditor();

		if (e != null) e.cancelCellEditing();
	}

	public void editingCanceled(ChangeEvent e) {
		originalRemoveEditor();
	}

	private void originalRemoveEditor() {
		TableCellEditor e = getCellEditor();

		if (e != null) e.removeCellEditorListener(listener());

		super.removeEditor();
	}

	public void removeEditor() {
		TableCellEditor e = getCellEditor();

		if (e != null) e.cancelCellEditing();
	}

	public boolean editCellAt(int row, int column, EventObject e) {
		// Don't call super.editCellAt() which installs the weird focus
		// lost editing *canceler* (1.4beta2).

		if (row < 0 || row >= getRowCount() || column < 0 || column >= getColumnCount()) throw new IllegalArgumentException();

		if (!isCellEditable(row, column)) return false;

		if (cellEditor != null && !cellEditor.stopCellEditing()) return false;

		// Insert better focus logic later

		TableCellEditor editor = getCellEditor(row, column);

		if (editor == null || !editor.isCellEditable(e)) return false;

		editorComp = prepareEditor(editor, row, column);

		if (editorComp == null) return false;

		setCellEditor(editor);
		setEditingRow(row);
		setEditingColumn(column);

		add(editorComp);
		editorComp.setBounds(getCellRect(row, column, false));
		editorComp.validate();

		// This is missing from the super class implementation.
		// It usually doesn't show.
		editorComp.repaint();

		// No need to *re*attach listener, since we can do it here
		// right in the first place
		editor.addCellEditorListener(listener());

		// It is very confusing to see another cell appear focused.
		// (If the focus is always given away to the editor, it doesn't show).
		getSelectionModel().setAnchorSelectionIndex(row);
		getColumnModel().getSelectionModel().setAnchorSelectionIndex(column);

		// This is not correct and partly a matter of taste:
		// (Maybe a property should be introduced like
		// surrendersFocusOwnKeystroke).
		// It is actually possible for editors to work properly even
		// if they initially don't have the focus (which already is the
		// usual case), but that is more complicated.
		// a) Some subcomponent may want the focus instead; the editor
		// component may not even be focusable. The same problems
		// has JTable with surrendersFocusOnKeystroke, it also requests
		// focus on the editor component. There should be method
		// in TableCellEditor to know the default focus component;
		// trying whether the component really is a JComponent and then
		// calling requestDefaultFocus() seems more like a heuristic
		// than something that has business in real code.
		// b) Should only do this if the focus is on the JTable?
		// But what if the JTable isn't in the focused window? Then one
		// cannot know whether it "has" focus in that window context
		// or not.
		getEditorComponent().requestFocus();

		return true;
	}

	public String getToolTipText(MouseEvent e) {
		String result = null;

		Point p = e.getPoint();

		int row = rowAtPoint(p), column = columnAtPoint(p);

		if (column != -1 && row != -1) {
			Rectangle r = getCellRect(row, column, false);

			if (r.contains(p)) // may be inside the intercell spacing
			{
				TableCellRenderer s = getCellRenderer(row, column);
				Component c = prepareRenderer(s, row, column);

				p.translate(-r.x, -r.y);

				c.setSize(r.width, r.height);

				if (c instanceof Container) {
					c.validate();

					Component d = ((Container) c).findComponentAt(p);

					for (Component cc = d; cc != c; cc = cc.getParent())
						p.translate(-cc.getX(), -cc.getY());

					c = d;
				}

				if (c instanceof JComponent) {
					MouseEvent f = new MouseEvent(c, e.getID(), e.getWhen(), e.getModifiers(), p.x, p.y, e.getClickCount(), e.isPopupTrigger());

					result = ((JComponent) c).getToolTipText(f);
				}
			}
		}

		if (result == null) result = getToolTipText();

		return result;
	}

	private static class TableColumnModels {
		private static int indexOf(TableColumnModel columns, TableColumn c) {
			for (int i = columns.getColumnCount() - 1; i >= 0; --i) {
				if (c.equals(columns.getColumn(i))) return i;
			}

			return -1;
		}

		private static void change(ListSelectionModel selection, int index, boolean toggle, boolean extend) {
			int anchor = selection.getAnchorSelectionIndex();

			change(selection, index, toggle, extend, selection.isSelectedIndex(index), anchor != -1 && selection.isSelectedIndex(anchor));
		}

		/**
		 * toggle extend selected index becomes
		 * anchor lead
		 * ====================================================================
		 * ==
		 * false false irrelevant select (only) index x x
		 * 
		 * true false false select index x x
		 * 
		 * true false true deselect index x x
		 * 
		 * true true irrelevant x
		 * 
		 * otherwise:
		 * false true* irrelevant x
		 * if (anchorSelected)
		 * deselect range between anchor and lead
		 * then select range between anchor and index
		 * else
		 * not (select range between anchor and lead)+
		 * then deselect range between anchor and index
		 * for single selection mode this implies:
		 * false true* irrelevant ? x
		 * if (anchor selected)
		 * select index (will implicitly deselect anchor if different)
		 * else
		 * deselect index (will change no selection if anchor != index)
		 * 
		 * ? : dependent on what the list selection model does
		 * (whether it ignores the first argument to set/removeSelection-
		 * Interval altogether in single selection mode)
		 * 
		 * if anchor is -1, regarded as false
		 * + this isn't done because it is very unintuitive.
		 * For symmetry it should be done, but ListSelectionModel isn't
		 * symmetric between selected and unselected indices in the first
		 * place. Indices that were never selected suddenly becoming it
		 * by changing a totally different cell would be strange.
		 */
		private static void change(ListSelectionModel selection, int index, boolean toggle, boolean extend, boolean selected, boolean anchorSelected) {
			if (toggle) {
				if (extend) selection.setAnchorSelectionIndex(index);
				else {
					if (selected) selection.removeSelectionInterval(index, index);
					else selection.addSelectionInterval(index, index);
				}
			} else {
				if (extend) {
					int anchor = selection.getAnchorSelectionIndex();

					if (anchor == -1) {
						if (selected) selection.removeSelectionInterval(index, index);
						else selection.addSelectionInterval(index, index);
					} else {
						int lead = selection.getLeadSelectionIndex();

						if (lead == -1) lead = index;

						if (anchorSelected) {
							boolean old = selection.getValueIsAdjusting();

							selection.setValueIsAdjusting(true);

							// This has some redundant tests, but minimizes
							// the number of potentially expensive calls to
							// remove-
							// SelectionInterval.

							if (anchor <= index) {
								if (index < lead) selection.removeSelectionInterval(index + 1, lead);
								else if (lead < anchor) selection.removeSelectionInterval(lead, anchor - 1);
							}
							if (index <= anchor) {
								if (lead < index) selection.removeSelectionInterval(lead, index - 1);
								else if (anchor < lead) selection.removeSelectionInterval(anchor + 1, lead);
							}

							selection.addSelectionInterval(anchor, index);

							selection.setValueIsAdjusting(old);
						} else {
							selection.removeSelectionInterval(anchor, index);
						}
					}
				} else {
					selection.setSelectionInterval(index, index);
				}
			}
		}
	}

	private boolean processKeyBindingImpl(KeyStroke k, KeyEvent e, int condition, boolean pressed) {
		if (isEnabled()) {
			Object binding = getInputMap(condition).get(k);

			if (binding == null) return false;

			Action a = getActionMap().get(binding);

			if (a != null) return SwingUtilities.notifyAction(a, k, e, this, e.getModifiers());
		}

		return false;
	}

	protected boolean processKeyBinding(KeyStroke k, KeyEvent e, int condition, boolean pressed) {
		if (condition == WHEN_ANCESTOR_OF_FOCUSED_COMPONENT && !isEditing()) return processKeyBindingImpl(k, e, condition, pressed);
		else return super.processKeyBinding(k, e, condition, pressed);
	}

	// cf. Thread 'DefaultCellEditor key handling' in comp.lang.java.gui.
	// postpone starting the editor after all other parties have been
	// given chance to handle/consume the event.
	protected void processKeyEvent(KeyEvent e) {
		super.processKeyEvent(e);

		if (!e.isConsumed() && !isEditing()) {
			KeyStroke k = KeyStroke.getKeyStrokeForEvent(e);

			if (super.processKeyBinding(k, e, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, e.getID() == KeyEvent.KEY_PRESSED)) {
				e.consume();
			}
		}
	}

	private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
		s.defaultReadObject();

		// If these are null, things are broken anyway.

		if (dataModel != null) dataModel.addTableModelListener(listener());

		if (selectionModel != null) selectionModel.addListSelectionListener(listener());

		if (columnModel != null) columnModel.addColumnModelListener(listener());

		if (cellEditor != null) cellEditor.addCellEditorListener(listener());
	}

	private Listener listener() {
		if (listener == null) listener = createListener();

		return listener;
	}

	private Listener createListener() {
		return new Listener();
	}

	// Delegates methods back to JTable, but is not Serializable.
	private class Listener implements TableModelListener, TableColumnModelListener, ListSelectionListener, CellEditorListener {
		public void tableChanged(TableModelEvent e) {
			FTable.this.tableChanged(e);
		}

		public void columnAdded(TableColumnModelEvent e) {
			FTable.this.columnAdded(e);
		}

		public void columnRemoved(TableColumnModelEvent e) {
			FTable.this.columnRemoved(e);
		}

		public void columnMoved(TableColumnModelEvent e) {
			FTable.this.columnMoved(e);
		}

		public void columnMarginChanged(ChangeEvent e) {
			FTable.this.columnMarginChanged(e);
		}

		public void columnSelectionChanged(ListSelectionEvent e) {
			FTable.this.columnSelectionChanged(e);
		}

		public void valueChanged(ListSelectionEvent e) {
			FTable.this.valueChanged(e);
		}

		public void editingStopped(ChangeEvent e) {
			FTable.this.editingStopped(e);
		}

		public void editingCanceled(ChangeEvent e) {
			FTable.this.editingCanceled(e);
		}
	}
	
	public static void main(String[] args) {
		FTable  t = new FTable(new AbstractTableModel() {
			
			@Override
			public Object getValueAt(int rowIndex, int columnIndex) {
				// TODO Auto-generated method stub
				return rowIndex+" : "+columnIndex;
			}
			
			@Override
			public int getRowCount() {
				return 10;
			}
			
			@Override
			public String getColumnName(int column) {
			
				return "Column "+column;
			}
			
			@Override
			public int getColumnCount() {
				return 5;
			}
		});
		JFrame f = new JFrame();
		f.getContentPane().add(new JScrollPane(t), BorderLayout.CENTER);
		f.setTitle("");
		f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		f.setSize(300, 300);
		f.setLocationRelativeTo(null);
		f.setVisible(true);
	}
}
