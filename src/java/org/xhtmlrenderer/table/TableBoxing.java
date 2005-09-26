/*
 * {{{ header & license
 * Copyright (c) 2004, 2005 Joshua Marinacci
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.	See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * }}}
 */
/*
   todo:
   recombine the tablelayout and tablelayout2
   move more of the calculation code into the table package
   make a set of tests in addition to the demo
   no span
   col span
   row span
   col and row span
   col span contents that are too big
   row span contents that are too big
   col and row span contents that are too big
   implement row height growing based on row spanned contents
   investigate margin collapsing
   support captions, headers, and footers
   - joshy
  */
package org.xhtmlrenderer.table;

import org.xhtmlrenderer.css.constants.CSSName;
import org.xhtmlrenderer.css.constants.IdentValue;
import org.xhtmlrenderer.css.newmatch.CascadedStyle;
import org.xhtmlrenderer.css.style.CalculatedStyle;
import org.xhtmlrenderer.css.value.Border;
import org.xhtmlrenderer.layout.BlockFormattingContext;
import org.xhtmlrenderer.layout.Boxing;
import org.xhtmlrenderer.layout.Context;
import org.xhtmlrenderer.layout.VerticalMarginCollapser;
import org.xhtmlrenderer.layout.content.Content;
import org.xhtmlrenderer.layout.content.TableCellContent;
import org.xhtmlrenderer.layout.content.TableContent;
import org.xhtmlrenderer.layout.content.TableRowContent;
import org.xhtmlrenderer.render.BlockBox;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.util.Uu;
import org.xhtmlrenderer.util.XRLog;

import javax.swing.*;
import java.awt.*;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;


/**
 * Description of the Class
 *
 * @author empty
 */
public class TableBoxing {

    /**
     * Description of the Method
     *
     * @param c       PARAM
     * @param content PARAM
     * @return Returns
     */
    public static Box layout(Context c, Content content) {
        Box outerBox;//the outer box may be block or inline block
        boolean set_bfc = false;
        if (content instanceof TableContent) {
            outerBox = new BlockBox();
            
            // install a block formatting context for the body,
            // ie. if it's null.
            // set up the outtermost bfc
            if (c.getBlockFormattingContext() == null) {
                outerBox.setParent(c.getCtx().getRootBox());
                BlockFormattingContext bfc = new BlockFormattingContext(outerBox, c);
                c.pushBFC(bfc);
                set_bfc = true;
                bfc.setWidth((int) c.getExtents().getWidth());
            }
        } else {
            XRLog.layout(Level.WARNING, "Unsupported table type " + content.getClass().getName());
            return null;
        }

        // copy the extents
        Rectangle oe = c.getExtents();
        c.setExtents(new Rectangle(oe));
        outerBox.x = c.getExtents().x;
        outerBox.y = c.getExtents().y;
        //HACK: for now
        outerBox.width = c.getExtents().width;
        outerBox.height = c.getExtents().height;

        TableBox tableBox = new TableBox();
        tableBox.element = content.getElement();
        //OK, first set up the current style. All depends on this...
        CascadedStyle pushed = content.getStyle();
        if (pushed != null) {
            c.pushStyle(pushed);
        } else {
            c.pushStyle(CascadedStyle.emptyCascadedStyle);
        }

        VerticalMarginCollapser.collapseVerticalMargins(c, tableBox, content, (float) oe.getWidth());

        TableContent tableContent = (TableContent) content;
        if (tableContent.isTopMarginCollapsed()) {
            tableBox.setMarginTopOverride(0f);
        }
        if (tableContent.isBottomMarginCollapsed()) {
            tableBox.setMarginBottomOverride(0f);
        }

        Border border = c.getCurrentStyle().getBorderWidth(c.getCtx());
        //note: percentages here refer to width of containing block
        Border margin = tableBox.getMarginWidth(c, (float) oe.getWidth());
        Border padding = c.getCurrentStyle().getPaddingWidth((float) oe.getWidth(), (float) oe.getWidth(), c.getCtx());
        int tx = margin.left + border.left + padding.left;
        int ty = margin.top + border.top + padding.top;
        tableBox.tx = tx;
        tableBox.ty = ty;
        c.translate(tx, ty);
        c.shrinkExtents(tx + margin.right + border.right + padding.right, ty + margin.bottom + border.bottom + padding.bottom);
        IdentValue borderStyle = c.getCurrentStyle().getIdent(CSSName.BORDER_COLLAPSE);
        int borderSpacingHorizontal = (int) c.getCurrentStyle().getFloatPropertyProportionalWidth(CSSName.FS_BORDER_SPACING_HORIZONTAL, 0, c.getCtx());
        int borderSpacingVertical = (int) c.getCurrentStyle().getFloatPropertyProportionalWidth(CSSName.FS_BORDER_SPACING_VERTICAL, 0, c.getCtx());
        layoutChildren(c, tableBox, content, false, borderSpacingHorizontal, borderSpacingVertical);
        c.unshrinkExtents();
        c.translate(-tx, -ty);
        //OK, now we basically have the maximum cell widths, is that a smart order?
        //TODO: percentages?
        if (c.getCurrentStyle().isIdent(CSSName.WIDTH, IdentValue.AUTO)) {
            //we're normally fine, unless the maximum width is greater than the extents
            fixWidths(tableBox, borderSpacingHorizontal);
        } else {//if the algorithm is fixed, we need to do something else from the start
            int givenWidth = (int) c.getCurrentStyle().getFloatPropertyProportionalWidth(CSSName.WIDTH, c.getExtents().width, c.getCtx());
            //also fine, if the total calculated is less than the extents and the width
            if (tableBox.width < givenWidth) {
                tableBox.width = givenWidth;
                fixWidths(tableBox, borderSpacingHorizontal);
            } else {
                c.getExtents().width = 1;
                c.translate(tx, ty);
                int[] preferredColumns = tableBox.columns;
                tableBox.columns = null;
                tableBox.removeAllChildren();
                tableBox.width = 0;
                tableBox.height = 0;
                layoutChildren(c, tableBox, content, false, borderSpacingHorizontal, borderSpacingVertical);
                c.translate(-tx, -ty);
                //here the table is layed out with minimum column widths
                if (tableBox.width < givenWidth) {
                    //do it right
                    tableBox.width = givenWidth;
                    tableBox.removeAllChildren();
                    fixWidths(tableBox, borderSpacingHorizontal, preferredColumns);
                    c.translate(tx, ty);
                    tableBox.width = 0;
                    tableBox.height = 0;
                    layoutChildren(c, tableBox, content, true, borderSpacingHorizontal, borderSpacingVertical);
                    c.translate(-tx, -ty);
                }
            }
        }
        //now the width is settled, fix vertical alignment
        for (Iterator i = tableBox.getChildIterator(); i.hasNext();) {
            Object o = i.next();
            if (o instanceof RowBox) {
                fixVerticalAlign(c, (RowBox) o);
            }
        }
        // calculate the total outer width
        tableBox.width = margin.left + border.left + padding.left + tableBox.width + padding.right + border.right + margin.right;
        tableBox.height = margin.top + border.top + padding.top + tableBox.height + padding.bottom + border.bottom + margin.bottom;

        c.popStyle();

        //restore the extents
        c.setExtents(oe);

        // remove the outtermost bfc
        if (set_bfc) {
            c.getBlockFormattingContext().doFinalAdjustments();
            //no! clear it in BasicPanel instead! c.popBFC();
        }
        return tableBox;//HACK:
    }

    private static void fixVerticalAlign(Context c, RowBox rowBox) {
        //TODO: improve this
        for (Iterator i = rowBox.getChildIterator(); i.hasNext();) {
            CellBox cell = (CellBox) i.next();
            if (cell.height < rowBox.height) cell.height = rowBox.height;
        }
    }

    /**
     * increases cell size without re-layout.
     */
    private static void fixWidths(TableBox tableBox, int borderSpacingHorizontal) {
        int sum = borderSpacingHorizontal;
        for (int i = 0; i < tableBox.columns.length; i++) sum += tableBox.columns[i] + borderSpacingHorizontal;
        if (sum < tableBox.width) {
            int extra = (tableBox.width - sum) / tableBox.columns.length;
            for (int i = 0; i < tableBox.columns.length; i++) tableBox.columns[i] += extra;
        } else {
            //if sum is greater, we probably screwed up earlier, just do something reasonable
            tableBox.width = sum;
        }
        for (Iterator tci = tableBox.getChildIterator(); tci.hasNext();) {
            Object tc = tci.next();
            if (tc instanceof RowBox) {
                RowBox row = (RowBox) tc;
                int col = 0;
                int x = borderSpacingHorizontal;
                for (Iterator cbi = row.getChildIterator(); cbi.hasNext();) {
                    CellBox cb = (CellBox) cbi.next();
                    cb.contentWidth = 0;
                    for (int i = 0; i < cb.colspan; i++) cb.contentWidth += tableBox.columns[col + i];
                    cb.contentWidth += borderSpacingHorizontal * (cb.colspan - 1);
                    cb.width = cb.contentWidth;
                    cb.x = x;
                    x += cb.width + borderSpacingHorizontal;
                    col += cb.colspan;
                }
            } else
                XRLog.layout(Level.WARNING, "Can't fix widths of " + tc.getClass().getName() + " yet!");
        }
    }

    /**
     * distributes extra space where needed. Needs a re-layout
     */
    private static void fixWidths(TableBox tableBox, int borderSpacingHorizontal, int[] preferredColumns) {
        int min = borderSpacingHorizontal;
        int wantMore = 0;
        for (int i = 0; i < tableBox.columns.length; i++) {
            min += tableBox.columns[i] + borderSpacingHorizontal;
            if (tableBox.columns[i] < preferredColumns[i]) wantMore++;
        }
        if (min < tableBox.width) {
            int extra = (tableBox.width - min) / wantMore;
            for (int i = 0; i < tableBox.columns.length; i++) {
                int wanted = preferredColumns[i] - tableBox.columns[i];
                if (wanted > 0) {
                    int added = (int) Math.min(extra, wanted);
                    tableBox.columns[i] += added;
                    min += added;
                }
            }
            //any left? just give it from the left
            for (int i = 0; i < tableBox.columns.length; i++) {
                int wanted = preferredColumns[i] - tableBox.columns[i];
                if (wanted > 0) {
                    int added = (int) Math.min(tableBox.width - min, wanted);
                    tableBox.columns[i] += added;
                    min += added;
                }
            }
        } else {
            //can't be less than the minimum possible, shouldn't normally get here
            tableBox.width = min;
        }
    }

    //TODO: do this right. It is totally as simple as possible.
    private static void layoutChildren(Context c, TableBox tableBox, Content content, boolean fixed, int borderSpacingHorizontal, int borderSpacingVertical) {
        Iterator contentIterator = content.getChildContent(c).iterator();
        while (contentIterator.hasNext()) {
            Object o = contentIterator.next();
            if (o instanceof TableRowContent) {
                c.translate(0, tableBox.height);
                RowBox row = layoutRow(c, (TableRowContent) o, tableBox, fixed, borderSpacingHorizontal, borderSpacingVertical);
                c.translate(0, -tableBox.height);

                tableBox.addChild(row);
                row.setParent(tableBox);
                row.element = ((TableRowContent) o).getElement();
                // set the child_box location
                row.x = 0;
                row.y = tableBox.height + borderSpacingVertical;

                // increase the final layout width if the child was greater
                if (row.width > tableBox.width) {
                    tableBox.width = row.width;
                }

                // increase the final layout height by the height of the child
                tableBox.height = row.y + row.height;
            } else {
                XRLog.layout(Level.WARNING, "Unsupported inside table: " + o.getClass().getName());
            }
        }
    }

    private static RowBox layoutRow(Context c, TableRowContent tableRowContent, TableBox table, boolean fixed, int borderSpacingHorizontal, int borderSpacingVertical) {
        // copy the extents
        Rectangle oe = c.getExtents();
        c.setExtents(new Rectangle(oe));
        RowBox row = new RowBox();
        CascadedStyle pushed = tableRowContent.getStyle();
        if (pushed != null) {
            c.pushStyle(pushed);
        } else {
            c.pushStyle(CascadedStyle.emptyCascadedStyle);
        }

        Border border = c.getCurrentStyle().getBorderWidth(c.getCtx());
        //note: percentages here refer to width of containing block
        Border margin = c.getCurrentStyle().getMarginWidth((float) oe.getWidth(), (float) oe.getWidth(), c.getCtx());
        Border padding = c.getCurrentStyle().getPaddingWidth((float) oe.getWidth(), (float) oe.getWidth(), c.getCtx());
        int tx = margin.left + border.left + padding.left;
        int ty = margin.top + border.top + padding.top;
        row.tx = tx;
        row.ty = ty;
        c.translate(tx, ty);
        c.shrinkExtents(tx + margin.right + border.right + padding.right, ty + margin.bottom + border.bottom + padding.bottom);
        List cells = tableRowContent.getChildContent(c);
        checkColumns(table, cells.size());
        layoutCells(cells, c, row, table, fixed, borderSpacingHorizontal, borderSpacingVertical);
        c.unshrinkExtents();
        c.translate(-tx, -ty);
        // calculate the total outer width
        row.width += borderSpacingHorizontal;
        row.width = margin.left + border.left + padding.left + row.width + padding.right + border.right + margin.right;
        row.height = margin.top + border.top + padding.top + row.height + padding.bottom + border.bottom + margin.bottom;

        c.popStyle();

        //restore the extents
        c.setExtents(oe);
        return row;
    }

    private static void layoutCells(List cells, Context c, RowBox row, TableBox table, boolean fixed, int borderSpacingHorizontal, int borderSpacingVertical) {
        int col = 0;
        for (Iterator i = cells.iterator(); i.hasNext();) {
            checkColumns(table, col + 1);
            if (table.columnRows[col] != 0) {
                col = col + 1;
                continue;
            }
            TableCellContent tcc = (TableCellContent) i.next();
            CellBox cellBox = new CellBox();
            c.translate(row.width, 0);
            c.setShrinkWrap();
            cellBox = (CellBox) layoutCell(c, cellBox, tcc, fixed, table, col);
            c.unsetShrinkWrap();
            c.translate(-row.width, 0);

            row.addChild(cellBox);
            cellBox.setParent(row);
            cellBox.element = tcc.getElement();
            // set the child_box location
            cellBox.x = row.width + borderSpacingHorizontal;
            row.y = 0;

            checkColumns(table, col + cellBox.colspan);
            for (int j = 0; j < cellBox.colspan; j++) {
                table.columnRows[col + j] = cellBox.rowspan;
                table.columnHeight[col + j] = cellBox.height;
                table.columnCell[col + j] = null;
            }
            table.columnCell[col] = cellBox;
            int width = 0;
            for (int j = 0; j < cellBox.colspan; j++) width += table.columns[col + j];
            if (!fixed && cellBox.width > width) {
                int extra = (cellBox.width - width) / cellBox.colspan;
                for (int j = 0; j < cellBox.colspan; j++) table.columns[col + j] += extra;
            }
            cellBox.contentWidth = 0;
            for (int j = 0; j < cellBox.colspan; j++) cellBox.contentWidth += table.columns[col + j];
            cellBox.contentWidth += (cellBox.colspan - 1) * borderSpacingHorizontal;
            cellBox.width = cellBox.contentWidth;
            row.width = cellBox.x + cellBox.width;
            col += cellBox.colspan;
            //this will be fixed again later!
            cellBox.height = 0;
        }
        for (int j = 0; j < table.columns.length; j++) {
            // increase the final layout height if the child was greater
            int height = table.columnHeight[j] / table.columnRows[j];
            if (height > row.height) {
                row.height = height;
            }
            table.columnHeight[j] -= height;
            table.columnRows[j]--;
        }
        for (int j = 0; j < table.columns.length; j++) {
            if (table.columnCell[j] == null) continue;
            table.columnCell[j].height += row.height;
            if (table.columnRows[j] != 0) {
                table.columnCell[j].height += borderSpacingVertical;
            }
        }
    }

    private static void checkColumns(TableBox table, int cols) {
        if (table.columns == null)
            table.columns = new int[cols];
        else if (table.columns.length < cols) {
            int[] newColumns = new int[cols];
            for (int i = 0; i < table.columns.length; i++) newColumns[i] = table.columns[i];
            table.columns = newColumns;
        }
        if (table.columnRows == null)
            table.columnRows = new int[cols];
        else if (table.columnRows.length < cols) {
            int[] newColumnRows = new int[cols];
            for (int i = 0; i < table.columnRows.length; i++) newColumnRows[i] = table.columnRows[i];
            table.columnRows = newColumnRows;
        }
        if (table.columnHeight == null)
            table.columnHeight = new int[cols];
        else if (table.columnHeight.length < cols) {
            int[] newColumnHeight = new int[cols];
            for (int i = 0; i < table.columnHeight.length; i++) newColumnHeight[i] = table.columnHeight[i];
            table.columnHeight = newColumnHeight;
        }
        if (table.columnCell == null)
            table.columnCell = new CellBox[cols];
        else if (table.columnCell.length < cols) {
            CellBox[] newColumnCell = new CellBox[cols];
            for (int i = 0; i < table.columnCell.length; i++) newColumnCell[i] = table.columnCell[i];
            table.columnCell = newColumnCell;
        }
    }

    /**
     * Description of the Method
     *
     * @param c       PARAM
     * @param block   PARAM
     * @param content PARAM
     * @return Returns
     */
    public static CellBox layoutCell(Context c, CellBox block, Content content, boolean fixed, TableBox table, int col) {
        //OK, first set up the current style. All depends on this...
        CascadedStyle pushed = content.getStyle();
        if (pushed != null) {
            c.pushStyle(pushed);
        }

        if (c.getCurrentStyle().isIdent(CSSName.BACKGROUND_ATTACHMENT, IdentValue.FIXED)) {
            block.setChildrenExceedBounds(true);
        }

        // install a block formatting context for the body,
        // ie. if it's null.
        // set up the outtermost bfc
        boolean set_bfc = false;
        if (c.getBlockFormattingContext() == null) {
            block.setParent(c.getCtx().getRootBox());
            BlockFormattingContext bfc = new BlockFormattingContext(block, c);
            c.pushBFC(bfc);
            set_bfc = true;
            bfc.setWidth((int) c.getExtents().getWidth());
        }

        // copy the extents
        Rectangle oe = c.getExtents();
        c.setExtents(new Rectangle(oe));

        block.colspan = (int) c.getCurrentStyle().getNumberProperty(CSSName.FS_COLSPAN);
        block.rowspan = (int) c.getCurrentStyle().getNumberProperty(CSSName.FS_ROWSPAN);
        if (fixed) {
            int width = 0;
            for (int i = 0; i < block.colspan; i++) width += table.columns[col + i];
            c.getExtents().width = width;
        }

        CalculatedStyle style = c.getCurrentStyle();
        boolean hasSpecifiedWidth = !style.isIdent(CSSName.WIDTH, IdentValue.AUTO);
        //TODO: handle relative heights, but only if containing block height is not defined by content height
        boolean hasSpecifiedHeight = !style.isIdent(CSSName.HEIGHT, IdentValue.AUTO);
        //HACK: assume containing block height is auto, so percentages become auto
        hasSpecifiedHeight = hasSpecifiedHeight && style.propertyByName(CSSName.HEIGHT).computedValue().hasAbsoluteUnit();

        // calculate the width and height as much as possible
        int setHeight = -1;//means height is not set by css
        int setWidth = -1;//means width is not set by css
        if (hasSpecifiedWidth) {
            setWidth = (int) style.getFloatPropertyProportionalWidth(CSSName.WIDTH, c.getExtents().width, c.getCtx());
            c.getExtents().width = setWidth;
            //TODO: CHECK: what does isSubBlock mean?
            if (!c.isSubBlock()) block.width = setWidth;
        }
        if (hasSpecifiedHeight) {
            setHeight = (int) style.getFloatPropertyProportionalHeight(CSSName.HEIGHT, c.getExtents().height, c.getCtx());
            c.getExtents().height = setHeight;
            block.height = setHeight;
            block.auto_height = false;
        }
        //check if replaced
        JComponent cc = c.getNamespaceHandler().getCustomComponent(content.getElement(), c, setWidth, setHeight);
        if (cc != null) {
            Rectangle bounds = cc.getBounds();
            //block.x = bounds.x;
            //block.y = bounds.y;
            block.width = bounds.width;
            block.height = bounds.height;
            block.component = cc;
        }
        block.x = c.getExtents().x;
        block.y = c.getExtents().y;

        /*if (ContentUtil.isFloated(content.getStyle())) {
            // set up a float bfc
            FloatUtil.preChildrenLayout(c, block);
        }

        if (Absolute.isAbsolute(content.getStyle())) {
            // set up an absolute bfc
            Absolute.preChildrenLayout(c, block);
        }


        if (c.getCurrentStyle().isIdent(CSSName.CLEAR, IdentValue.LEFT)) {
            block.clear_left = true;
        }
        if (c.getCurrentStyle().isIdent(CSSName.CLEAR, IdentValue.RIGHT)) {
            block.clear_right = true;
        }
        if (c.getCurrentStyle().isIdent(CSSName.CLEAR, IdentValue.BOTH)) {
            block.clear_left = true;
            block.clear_right = true;
        }
        if (c.getCurrentStyle().isIdent(CSSName.CLEAR, IdentValue.NONE)) {
            block.clear_left = false;
            block.clear_right = false;
        }*/


        // save height incase fixed height
        int original_height = block.height;

        // do children's layout
        boolean old_sub = c.isSubBlock();
        c.setSubBlock(false);
        Border border = c.getCurrentStyle().getBorderWidth(c.getCtx());
        Border padding = c.getCurrentStyle().getPaddingWidth((float) oe.getWidth(), (float) oe.getWidth(), c.getCtx());
        int tx = border.left + padding.left;
        int ty = border.top + padding.top;
        block.tx = tx;
        block.ty = ty;
        c.translate(tx, ty);
        c.shrinkExtents(tx + border.right + padding.right, ty + border.bottom + padding.bottom);
        if (block.component == null)
            Boxing.layoutChildren(c, block, content);//when this is really an anonymous, InlineLayout.layoutChildren is called
        else {
            Point origin = c.getOriginOffset();
            block.component.setLocation((int) origin.getX(), (int) origin.getY());
            if (c.isInteractive()) {
                c.getCanvas().add(block.component);
            }
        }
        c.unshrinkExtents();
        c.translate(-tx, -ty);
        c.setSubBlock(old_sub);

        // restore height incase fixed height
        if (block.auto_height == false) {
            Uu.p("restoring original height");
            block.height = original_height;
        }

        /*if (ContentUtil.isFloated(content.getStyle())) {
            // remove the float bfc
            FloatUtil.postChildrenLayout(c);
        }

        if (Absolute.isAbsolute(content.getStyle())) {
            // remove the absolute bfc
            Absolute.postChildrenLayout(c);
        }*/

        // calculate the total outer width
        block.contentWidth = block.width;
        block.width = border.left + padding.left + block.width + padding.right + border.right;
        block.height = border.top + padding.top + block.height + padding.bottom + border.bottom;

        //restore the extents
        c.setExtents(oe);

        // account for special positioning
        // need to add bfc/unbfc code for absolutes
        //Absolute.setupAbsolute(block, c);
        //Fixed.setupFixed(c, block);
        //FloatUtil.setupFloat(c, block, content.getStyle());

        // remove the outtermost bfc
        if (set_bfc) {
            c.getBlockFormattingContext().doFinalAdjustments();
            //no! clear it in BasicPanel instead! c.popBFC();
        }

        //and now, back to previous style
        if (pushed != null) {
            c.popStyle();
        }

        // Uu.p("BoxLayout: finished with block: " + block);
        return block;
    }

}

/*
   $Id$
   $Log$
   Revision 1.23  2005/09/26 22:40:23  tobega
   Applied patch from Peter Brant concerning margin collapsing

   Revision 1.22  2005/09/11 20:43:16  tobega
   Fixed table-css interaction bug, colspan now works again

   Revision 1.21  2005/08/03 21:44:00  tobega
   Now support rowspan

   Revision 1.20  2005/07/05 06:10:30  tobega
   text-align now works for table-cells (fixed an omission)

   Revision 1.19  2005/07/04 00:12:13  tobega
   text-align now works for table-cells too (is done in render, not in layout)

   Revision 1.18  2005/07/02 12:25:44  tobega
   colspan is working!

   Revision 1.17  2005/06/22 23:48:46  tobega
   Refactored the css package to allow a clean separation from the core.

   Revision 1.16  2005/06/19 23:02:38  tobega
   Implemented calculation of minimum cell-widths.
   Implemented border-spacing.

   Revision 1.15  2005/06/09 21:35:02  tobega
   Increases cells to fill out a given table width, otherwise just does something reasonable for now

   Revision 1.14  2005/06/08 19:48:55  tobega
   Rock 'n roll! Report looks quite good!

   Revision 1.13  2005/06/08 19:01:57  tobega
   Table cells get their preferred width

   Revision 1.12  2005/06/08 18:24:52  tobega
   Starting to get some kind of shape to tables

   Revision 1.11  2005/06/05 01:02:35  tobega
   Very simple and not completely functional table layout

   Revision 1.10  2005/05/13 15:23:57  tobega
   Done refactoring box borders, margin and padding. Hover is working again.

   Revision 1.9  2005/05/08 14:36:59  tobega
   Refactored away the need for having a context in a CalculatedStyle

   Revision 1.8  2005/01/29 20:18:43  pdoubleya
   Clean/reformat code. Removed commented blocks, checked copyright.

   Revision 1.7  2005/01/24 22:46:46  pdoubleya
   Added support for ident-checks using IdentValue instead of string comparisons.

   Revision 1.6  2005/01/24 19:01:09  pdoubleya
   Mass checkin. Changed to use references to CSSName, which now has a Singleton instance for each property, everywhere property names were being used before. Removed commented code. Cascaded and Calculated style now store properties in arrays rather than maps, for optimization.

   Revision 1.5  2005/01/24 14:36:36  pdoubleya
   Mass commit, includes: updated for changes to property declaration instantiation, and new use of DerivedValue. Removed any references to older XR... classes (e.g. XRProperty). Cleaned imports.

   Revision 1.4  2005/01/16 18:50:07  tobega
   Re-introduced caching of styles, which make hamlet and alice scroll nicely again. Background painting still slow though.

   Revision 1.3  2005/01/07 00:29:31  tobega
   Removed Content reference from Box (mainly to reduce memory footprint). In the process stumbled over and cleaned up some messy stuff.

   Revision 1.2  2005/01/02 12:22:21  tobega
   Cleaned out old layout code

   Revision 1.1  2005/01/02 09:32:41  tobega
   Now using mostly static methods for layout

   Revision 1.16  2005/01/01 23:38:41  tobega
   Cleaned out old rendering code

   Revision 1.15  2004/12/29 10:39:36  tobega
   Separated current state Context into ContextImpl and the rest into SharedContext.

   Revision 1.14  2004/12/29 07:35:40  tobega
   Prepared for cloned Context instances by encapsulating fields

   Revision 1.13  2004/12/27 07:43:33  tobega
   Cleaned out border from box, it can be gotten from current style. Is it maybe needed for dynamic stuff?

   Revision 1.12  2004/12/12 04:18:58  tobega
   Now the core compiles at least. Now we must make it work right. Table layout is one point that really needs to be looked over

   Revision 1.11  2004/12/12 03:33:03  tobega
   Renamed x and u to avoid confusing IDE. But that got cvs in a twist. See if this does it

   Revision 1.10  2004/12/09 21:18:53  tobega
   precaution: code still works

   Revision 1.9  2004/12/09 00:11:53  tobega
   Almost ready for Content-based inline generation.

   Revision 1.8  2004/12/05 00:49:00  tobega
   Cleaned up so that now all property-lookups use the CalculatedStyle. Also added support for relative values of top, left, width, etc.

   Revision 1.7  2004/11/19 14:39:08  joshy
   fixed crash when a tr is empty

   Issue number:
   Obtained from:
   Submitted by:
   Reviewed by:

   Revision 1.6  2004/11/19 14:27:38  joshy
   removed hard coded element names
   added support for tbody, or tbody missing



   Issue number:
   Obtained from:
   Submitted by:
   Reviewed by:

   Revision 1.5  2004/11/14 16:41:04  joshy
   refactored layout factory

   Issue number:
   Obtained from:
   Submitted by:
   Reviewed by:

   Revision 1.4  2004/10/28 01:34:26  joshy
   moved more painting code into the renderers

   Issue number:
   Obtained from:
   Submitted by:
   Reviewed by:

   Revision 1.3  2004/10/23 13:59:18  pdoubleya
   Re-formatted using JavaStyle tool.
   Cleaned imports to resolve wildcards except for common packages (java.io, java.util, etc).
   Added CVS log comments at bottom.

  */

