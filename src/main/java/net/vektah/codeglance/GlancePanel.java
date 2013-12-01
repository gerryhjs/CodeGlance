/*
 * Copyright © 2013, Adam Scarr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.vektah.codeglance;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import net.vektah.codeglance.config.Config;
import net.vektah.codeglance.config.ConfigChangeListener;
import net.vektah.codeglance.config.ConfigService;
import net.vektah.codeglance.render.CoordinateHelper;
import net.vektah.codeglance.render.Minimap;
import net.vektah.codeglance.render.RenderTask;
import net.vektah.codeglance.render.TaskRunner;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Array;

/**
 * This JPanel gets injected into editor windows and renders a image generated by GlanceFileRenderer
 */
public class GlancePanel extends JPanel implements VisibleAreaListener {
	public static final int MAX_WIDTH = 110;        // TODO: This should probably be a config option.
	private final TaskRunner runner;
	private Editor editor;
	private Minimap[] minimaps = new Minimap[2];
	private Integer activeBuffer = -1;
	private Integer nextBuffer = 0;
	private JPanel container;
	private Logger logger = Logger.getInstance(getClass().getName());
	private Project project;
	private Boolean updatePending = false;
	private boolean dirty = false;
	private CoordinateHelper coords = new CoordinateHelper();
	private ConfigService configService = ServiceManager.getService(ConfigService.class);
	private Config config;
	private int lastFoldCount = -1;

	// Anonymous Listeners that should be cleaned up.
	private ComponentListener componentListener;
	private DocumentListener documentListener;
	private ConfigChangeListener configChangeListener;
	private MouseWheelListener mouseWheelListener = new MouseWheelListener();
	private MouseListener mouseListener = new MouseListener();

	public GlancePanel(Project project, FileEditor fileEditor, JPanel container, TaskRunner runner) {
		this.runner = runner;
		this.editor = ((TextEditor) fileEditor).getEditor();
		this.container = container;
		this.project = project;

		container.addComponentListener(componentListener = new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent componentEvent) {
				updateSize();
				GlancePanel.this.revalidate();
				GlancePanel.this.repaint();
			}
		});

		editor.getDocument().addDocumentListener(documentListener = new DocumentAdapter() {
			@Override public void documentChanged(DocumentEvent documentEvent) {
				updateImage();
			}
		});

		configService.add(configChangeListener = new ConfigChangeListener() {
			@Override public void configChanged() {
				readConfig();
				updateImage();
				updateSize();
				GlancePanel.this.revalidate();
				GlancePanel.this.repaint();
			}
		});

		addMouseWheelListener(mouseWheelListener);

		readConfig();

		editor.getScrollingModel().addVisibleAreaListener(this);
		addMouseListener(mouseListener);
		addMouseMotionListener(mouseListener);

		updateSize();
		for(int i = 0; i < Array.getLength(minimaps); i++) {
			minimaps[i] = new Minimap(configService.getState());
		}
		updateImage();
	}

	private void readConfig() {
		config = configService.getState();

		coords.setPixelsPerLine(config.pixelsPerLine);
	}

	/**
	 * Adjusts the panels size to be a percentage of the total window
	 */
	private void updateSize() {
		if (config.disabled) {
			setPreferredSize(new Dimension(0, 0));
		} else {
			// Window should not take up more then 10%
			int percentageWidth = container.getWidth() / 10;
			// but shouldn't be too wide either. 100 chars wide should be enough to visualize a code outline.
			int totalWidth = Math.min(percentageWidth, MAX_WIDTH);

			Dimension size = new Dimension(totalWidth, 0);
			setPreferredSize(size);
		}
	}

	/**
	 * Fires off a new task to the worker thread. This should only be called from the ui thread.
	 */
	private void updateImage() {
		if (config.disabled) return;
		if (project.isDisposed()) return;

		PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		if (file == null) {
			return;
		}

		synchronized (this) {
			// If we have already sent a rendering job off to get processed then first we need to wait for it to finish.
			// see updateComplete for dirty handling. The is that there will be fast updates plus one final update to
			// ensure accuracy, dropping any requests in the middle.
			if (updatePending) {
				dirty = true;
				return;
			}
			updatePending = true;
		}

		SyntaxHighlighter hl = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage(), project, file.getVirtualFile());

		nextBuffer = activeBuffer == 0 ? 1 : 0;

		runner.add(new RenderTask(minimaps[nextBuffer], editor.getDocument().getText(), editor.getColorsScheme(), hl, editor.getFoldingModel().getAllFoldRegions(), new Runnable() {
			@Override public void run() {
				updateComplete();
			}
		}));
	}

	private void updateComplete() {
		synchronized (this) {
			updatePending = false;
		}

		if (dirty) {
			SwingUtilities.invokeLater(new Runnable() {
				@Override public void run() {
					updateImage();
					dirty = false;
				}
			});
		}

		activeBuffer = nextBuffer;

		repaint();
	}

	private float getHidpiScale() {
		// Work around for apple going full retard with half pixel pixels.
		Float scale = (Float)Toolkit.getDefaultToolkit().getDesktopProperty("apple.awt.contentScaleFactor");
		if (scale == null) {
			scale = 1.0f;
		}

		return scale;
	}

	private int getMapYFromEditorY(int y) {
		int offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(new Point(0, y)));

		return coords.offsetToScreenSpace(offset);
	}

	@Override
	public void paint(Graphics g) {
		g.setColor(editor.getColorsScheme().getDefaultBackground());
		g.fillRect(0, 0, getWidth(), getHeight());

		logger.debug(String.format("Rendering to buffer: %d", activeBuffer));
		if (activeBuffer >= 0) {
			Minimap minimap = minimaps[activeBuffer];

			Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();

			double documentEndY = editor.logicalPositionToXY(editor.offsetToLogicalPosition(editor.getDocument().getTextLength() - 1)).getY();

			coords.setMinimap(minimap)
				.setPanelHeight(getHeight())
				.setPanelWidth(getWidth())
				.setPercentageComplete(visibleArea.getMinY() / (documentEndY - (visibleArea.getMaxY() - visibleArea.getMinY())))
				.setHidpiScale(getHidpiScale());

			Rectangle src = coords.getImageSource();
			Rectangle dest = coords.getImageDestination();

			// Draw the image and scale it to stretch vertically.
			g.drawImage(minimap.img,                                                    // source image
					dest.x, dest.y, dest.width, dest.height,
					src.x, src.y, src.width, src.height,
					null);


			g.setColor(JBColor.GRAY);
			Graphics2D g2d = (Graphics2D) g;

			int firstVisibleLine =  getMapYFromEditorY((int) visibleArea.getMinY());
			int height = coords.linesToPixels((int) ((visibleArea.getMaxY() - visibleArea.getMinY()) / editor.getLineHeight()));

			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.40f));
			g2d.drawRect(0, firstVisibleLine, getWidth(), height);
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f));
			g2d.fillRect(0, firstVisibleLine, getWidth(), height);
		}
	}

	@Override public void visibleAreaChanged(VisibleAreaEvent visibleAreaEvent) {
		// TODO pending http://youtrack.jetbrains.com/issue/IDEABKL-1141 - once fixed this should be a listener
		int currentFoldCount = 0;
		for (FoldRegion fold: editor.getFoldingModel().getAllFoldRegions()) {
			if (!fold.isExpanded()) {
				currentFoldCount++;
			}
		}

		if (currentFoldCount != lastFoldCount) {
			updateImage();
		}

		lastFoldCount = currentFoldCount;

		updateSize();
		repaint();
	}

	public void onClose() {
		container.removeComponentListener(componentListener);
		editor.getDocument().removeDocumentListener(documentListener);
		configService.remove(configChangeListener);
		editor.getScrollingModel().removeVisibleAreaListener(this);
		removeMouseWheelListener(mouseWheelListener);
		removeMouseListener(mouseListener);
		removeMouseMotionListener(mouseListener);

		componentListener = null;
		documentListener = null;
		configChangeListener = null;
		mouseListener = null;
	}

	private class MouseWheelListener implements java.awt.event.MouseWheelListener {
		@Override public void mouseWheelMoved(MouseWheelEvent mouseWheelEvent) {
			logger.warn(Integer.toString(mouseWheelEvent.getWheelRotation()));
			editor.getScrollingModel().scrollVertically(editor.getScrollingModel().getVerticalScrollOffset() + (mouseWheelEvent.getWheelRotation() * editor.getLineHeight() * 3));
		}
	}

	private class MouseListener extends MouseAdapter {
		private int scrollStart;
		private int mouseStart;

		@Override public void mouseDragged(MouseEvent e) {
			// Disable animation when dragging for better experience.
			editor.getScrollingModel().disableAnimation();

			editor.getScrollingModel().scrollVertically(scrollStart + coords.pixelsToLines(e.getY() - mouseStart) * editor.getLineHeight());
			editor.getScrollingModel().enableAnimation();
		}

		@Override public void mousePressed(MouseEvent e) {
			Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();
			int firstVisibleLine =  getMapYFromEditorY((int) visibleArea.getMinY());
			int height = coords.linesToPixels((int) ((visibleArea.getMaxY() - visibleArea.getMinY()) / editor.getLineHeight()));

			int panelY = e.getY() - getY();

			if (config.jumpOnMouseDown && (panelY <= firstVisibleLine || panelY >= (firstVisibleLine + height))) {
				editor.getScrollingModel().disableAnimation();
				editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(coords.screenSpaceToOffset(e.getY(), config.percentageBasedClick)), ScrollType.CENTER);
				editor.getScrollingModel().enableAnimation();
			}

			scrollStart = editor.getScrollingModel().getVerticalScrollOffset();
			mouseStart = e.getY();
		}

		@Override public void mouseClicked(MouseEvent e) {
			if (!config.jumpOnMouseDown) {
				editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(coords.screenSpaceToOffset(e.getY(), config.percentageBasedClick)), ScrollType.CENTER);
			}
		}
	}
}
