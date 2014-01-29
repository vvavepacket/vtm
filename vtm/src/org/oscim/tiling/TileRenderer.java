/*
 * Copyright 2013 Hannes Janetzek
 *
 * This file is part of the OpenScienceMap project (http://www.opensciencemap.org).
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.tiling;

import static org.oscim.tiling.MapTile.STATE_NEW_DATA;
import static org.oscim.tiling.MapTile.STATE_READY;

import org.oscim.backend.GL20;
import org.oscim.backend.canvas.Color;
import org.oscim.core.MapPosition;
import org.oscim.core.Tile;
import org.oscim.renderer.BufferObject;
import org.oscim.renderer.ElementRenderer;
import org.oscim.renderer.LayerRenderer;
import org.oscim.renderer.MapRenderer;
import org.oscim.renderer.MapRenderer.Matrices;
import org.oscim.renderer.elements.BitmapLayer;
import org.oscim.renderer.elements.LineLayer;
import org.oscim.renderer.elements.LineTexLayer;
import org.oscim.renderer.elements.MeshLayer;
import org.oscim.renderer.elements.PolygonLayer;
import org.oscim.renderer.elements.RenderElement;
import org.oscim.utils.FastMath;
import org.oscim.utils.ScanBox;
import org.oscim.utils.quadtree.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TileRenderer extends LayerRenderer {
	static final Logger log = LoggerFactory.getLogger(TileRenderer.class);

	private static final boolean debugOverdraw = false;

	private final TileManager mTileManager;
	private int mUploadSerial;

	public TileRenderer(TileManager tileManager) {
		mTileManager = tileManager;
		mUploadSerial = 0;
	}

	private int mOverdraw = 0;
	private float mAlpha = 1;

	private int mRenderOverdraw;
	private float mRenderAlpha;

	/**
	 * Threadsafe
	 */
	public synchronized void setOverdrawColor(int color) {
		mOverdraw = color;
	}

	/**
	 * Threadsafe
	 */
	public synchronized void setBitmapAlpha(float alpha) {
		mAlpha = alpha;
	}

	/**
	 * synced with clearTiles, setOverdrawColor and setBitmapAlpha
	 */
	@Override
	protected synchronized void update(MapPosition pos, boolean positionChanged, Matrices m) {

		if (mAlpha == 0) {
			mTileManager.releaseTiles(mDrawTiles);
			return;
		}

		// get current tiles to draw
		boolean tilesChanged;
		synchronized (tilelock) {
			tilesChanged = mTileManager.getActiveTiles(mDrawTiles);
		}

		if (mDrawTiles.cnt == 0)
			return;

		// keep constant while rendering frame
		mRenderAlpha = mAlpha;
		mRenderOverdraw = mOverdraw;

		int tileCnt = mDrawTiles.cnt;
		MapTile[] tiles = mDrawTiles.tiles;

		if (tilesChanged || positionChanged) {
			updateTileVisibility(pos, m.mapPlane);
		}

		tileCnt += mNumTileHolder;

		// prepare tiles for rendering
		if (compileTileLayers(tiles, tileCnt) > 0) {
			mUploadSerial++;
			BufferObject.checkBufferUsage(false);
		}

		draw(tiles, tileCnt, pos, m);
	}

	public void clearTiles() {
		// Clear all references to MapTiles as all current
		// tiles will also be removed from TileManager.
		mDrawTiles = new TileSet();
	}

	/** compile tile layer data and upload to VBOs */
	private static int compileTileLayers(MapTile[] tiles, int tileCnt) {
		int uploadCnt = 0;

		for (int i = 0; i < tileCnt; i++) {
			MapTile tile = tiles[i];

			if (!tile.isVisible)
				continue;

			if (tile.state == STATE_READY)
				continue;

			if (tile.state == STATE_NEW_DATA) {
				uploadCnt += uploadTileData(tile);
				continue;
			}

			if (tile.holder != null) {
				// load tile that is referenced by this holder
				if (tile.holder.state == STATE_NEW_DATA)
					uploadCnt += uploadTileData(tile.holder);

				tile.state = tile.holder.state;
				continue;
			}

			// check near relatives than can serve as proxy
			if ((tile.proxies & MapTile.PROXY_PARENT) != 0) {
				MapTile rel = tile.node.parent.item;
				if (rel.state == STATE_NEW_DATA)
					uploadCnt += uploadTileData(rel);

				// dont load child proxies
				continue;
			}

			for (int c = 0; c < 4; c++) {
				if ((tile.proxies & 1 << c) == 0)
					continue;

				MapTile rel = tile.node.child(i);
				if (rel != null && rel.state == STATE_NEW_DATA)
					uploadCnt += uploadTileData(rel);
			}
		}
		return uploadCnt;
	}

	private static int uploadTileData(MapTile tile) {
		tile.state = STATE_READY;

		// tile might contain extrusion or label layers
		if (tile.layers == null)
			return 1;

		int newSize = tile.layers.getSize();
		if (newSize > 0) {

			if (tile.layers.vbo == null)
				tile.layers.vbo = BufferObject.get(GL20.GL_ARRAY_BUFFER, newSize);

			if (!ElementRenderer.uploadLayers(tile.layers, newSize, true)) {
				log.debug("BUG uploadTileData " + tile + " failed!");

				tile.layers.vbo = BufferObject.release(tile.layers.vbo);
				tile.layers.clear();
				tile.layers = null;
				return 0;
			}
		}

		return 1;
	}

	private final Object tilelock = new Object();

	/** set tile isVisible flag true for tiles that intersect view */
	private void updateTileVisibility(MapPosition pos, float[] box) {

		// lock tiles while updating isVisible state
		synchronized (tilelock) {
			MapTile[] tiles = mDrawTiles.tiles;

			int tileZoom = tiles[0].zoomLevel;

			for (int i = 0; i < mDrawTiles.cnt; i++)
				tiles[i].isVisible = false;

			// count placeholder tiles
			mNumTileHolder = 0;

			// check visibile tiles
			mScanBox.scan(pos.x, pos.y, pos.scale, tileZoom, box);
		}
	}

	/**
	 * Update tileSet with currently visible tiles get a TileSet of currently
	 * visible tiles
	 */
	public boolean getVisibleTiles(TileSet tileSet) {
		if (tileSet == null)
			return false;

		if (mDrawTiles == null) {
			releaseTiles(tileSet);
			return false;
		}

		int prevSerial = tileSet.serial;

		// ensure tiles keep visible state
		synchronized (tilelock) {

			MapTile[] newTiles = mDrawTiles.tiles;
			int cnt = mDrawTiles.cnt;

			// unlock previous tiles
			tileSet.releaseTiles();

			// ensure same size
			if (tileSet.tiles.length != newTiles.length) {
				tileSet.tiles = new MapTile[newTiles.length];
			}

			// lock tiles to not be removed from cache
			tileSet.cnt = 0;
			for (int i = 0; i < cnt; i++) {
				MapTile t = newTiles[i];
				if (t.isVisible && t.state == STATE_READY) {
					t.lock();
					tileSet.tiles[tileSet.cnt++] = t;
				}
			}

			tileSet.serial = mUploadSerial;
		}

		return prevSerial != tileSet.serial;
	}

	public void releaseTiles(TileSet tileSet) {
		tileSet.releaseTiles();
	}

	// Add additional tiles that serve as placeholer when flipping
	// over date-line.
	// I dont really like this but cannot think of a better solution:
	// The other option would be to run scanbox each time for upload,
	// drawing, proxies and text layer. Adding placeholder only
	// happens rarely, unless you live on Fidschi

	/* package */int mNumTileHolder;
	/* package */TileSet mDrawTiles = new TileSet();

	// scanline fill class used to check tile visibility
	private final ScanBox mScanBox = new ScanBox() {
		@Override
		protected void setVisible(int y, int x1, int x2) {
			int cnt = mDrawTiles.cnt;

			MapTile[] tiles = mDrawTiles.tiles;

			for (int i = 0; i < cnt; i++) {
				MapTile t = tiles[i];
				if (t.tileY == y && t.tileX >= x1 && t.tileX < x2)
					t.isVisible = true;
			}

			// add placeholder tiles to show both sides
			// of date line. a little too complicated...
			int xmax = 1 << mZoom;
			if (x1 >= 0 && x2 < xmax)
				return;

			O: for (int x = x1; x < x2; x++) {
				if (x >= 0 && x < xmax)
					continue;

				int xx = x;
				if (x < 0)
					xx = xmax + x;
				else
					xx = x - xmax;

				if (xx < 0 || xx >= xmax)
					continue;

				for (int i = cnt; i < cnt + mNumTileHolder; i++)
					if (tiles[i].tileX == x && tiles[i].tileY == y)
						continue O;

				MapTile tile = null;
				for (int i = 0; i < cnt; i++)
					if (tiles[i].tileX == xx && tiles[i].tileY == y) {
						tile = tiles[i];
						break;
					}

				if (tile == null)
					continue;

				if (cnt + mNumTileHolder >= tiles.length) {
					//log.error(" + mNumTileHolder");
					break;
				}
				MapTile holder = new MapTile(x, y, (byte) mZoom);
				holder.isVisible = true;
				holder.holder = tile;
				tile.isVisible = true;
				tiles[cnt + mNumTileHolder++] = holder;
			}
		}
	};

	private long getMinFade(MapTile t) {
		long maxFade = MapRenderer.frametime - 50;

		for (int c = 0; c < 4; c++) {
			MapTile ci = t.node.child(c);
			if (ci == null)
				continue;

			if (ci.state == MapTile.STATE_READY || ci.fadeTime > 0)
				maxFade = Math.min(maxFade, ci.fadeTime);
		}
		MapTile p = t.node.parent();
		if (p != null && (p.state == MapTile.STATE_READY || p.fadeTime > 0)) {
			maxFade = Math.min(maxFade, p.fadeTime);

			p = p.node.parent();
			if (p != null && (p.state == MapTile.STATE_READY || p.fadeTime > 0))
				maxFade = Math.min(maxFade, p.fadeTime);
		}

		return maxFade;
	}

	// Current number of frames drawn, used to not draw a
	// tile twice per frame.
	private int mDrawSerial = 0;
	private Matrices mMatrices;

	/**
	 * Draw tiles:
	 * 
	 * @param fade
	 *            alpha value for bitmap tiles
	 * @param overdrawColor
	 *            draw color on top, e.g. to darken the layer temporarily
	 */
	private void draw(MapTile[] tiles, int tileCnt, MapPosition pos, Matrices m) {

		mMatrices = m;

		/** draw visible tiles */
		for (int i = 0; i < tileCnt; i++) {
			MapTile t = tiles[i];
			if (t.isVisible && t.state == STATE_READY)
				drawTile(t, pos);
		}

		/**
		 * draw parent or children as proxy for visibile tiles that dont
		 * have data yet. Proxies are clipped to the region where nothing
		 * was drawn to depth buffer.
		 * TODO draw proxies for placeholder
		 */
		double scale = pos.getZoomScale();

		for (int i = 0; i < tileCnt; i++) {
			MapTile t = tiles[i];
			if (t.isVisible && (t.state != STATE_READY) && (t.holder == null)) {
				boolean preferParent = (scale > 1.5) || (pos.zoomLevel - t.zoomLevel < 0);
				drawProxyTile(t, pos, true, preferParent);
			}
		}

		/** draw grandparents */
		for (int i = 0; i < tileCnt; i++) {
			MapTile t = tiles[i];
			if (t.isVisible && (t.state != STATE_READY) && (t.holder == null))
				drawProxyTile(t, pos, false, false);
		}

		/** make sure stencil buffer write is disabled */
		GL.glStencilMask(0x00);
		GL.glDepthMask(false);

		mDrawSerial++;
		mMatrices = null;
	}

	private void drawTile(MapTile tile, MapPosition pos) {
		// draw parents only once
		if (tile.lastDraw == mDrawSerial)
			return;

		tile.lastDraw = mDrawSerial;

		MapTile t = tile;
		if (t.holder != null)
			t = t.holder;

		if (t.layers == null || t.layers.vbo == null) {
			//log.debug("missing data " + (t.layers == null) + " " + (t.vbo == null));
			return;
		}

		t.layers.vbo.bind();

		// place tile relative to map position
		int z = tile.zoomLevel;

		float div = FastMath.pow(z - pos.zoomLevel);

		double tileScale = Tile.SIZE * pos.scale;
		float x = (float) ((tile.x - pos.x) * tileScale);
		float y = (float) ((tile.y - pos.y) * tileScale);

		// scale relative to zoom-level of this tile
		float scale = (float) (pos.scale / (1 << z));

		Matrices m = mMatrices;
		m.mvp.setTransScale(x, y, scale / MapRenderer.COORD_SCALE);
		m.mvp.multiplyLhs(m.viewproj);

		boolean clipped = false;

		for (RenderElement l = t.layers.baseLayers; l != null;) {
			switch (l.type) {
				case RenderElement.POLYGON:
					l = PolygonLayer.Renderer.draw(pos, l, m, !clipped, div, true);
					clipped = true;
					break;

				case RenderElement.LINE:
					if (!clipped) {
						clipped = true;
						PolygonLayer.Renderer.draw(pos, null, m, true, div, true);
					}
					l = LineLayer.Renderer.draw(t.layers, l, pos, m, scale);
					break;

				case RenderElement.TEXLINE:
					if (!clipped) {
						clipped = true;
						PolygonLayer.Renderer.draw(pos, null, m, true, div, true);
					}
					l = LineTexLayer.Renderer.draw(t.layers, l, pos, m, div);
					break;

				case RenderElement.MESH:
					if (!clipped) {
						clipped = true;
						PolygonLayer.Renderer.draw(pos, null, m, true, div, true);
					}
					l = MeshLayer.Renderer.draw(pos, l, m);
					break;

				default:
					// just in case
					l = l.next;
			}
		}

		for (RenderElement l = t.layers.textureLayers; l != null;) {
			if (!clipped) {
				// draw stencil buffer clip region
				PolygonLayer.Renderer.draw(pos, null, m, true, div, true);
				clipped = true;
			}
			//			if (!clipped) {
			//				// draw stencil buffer clip region
			//				PolygonRenderer.clip(m);
			//				clipped = true;
			//			}
			//GLState.test(false, false);
			switch (l.type) {
				case RenderElement.BITMAP:
					l = BitmapLayer.Renderer.draw(l, m, 1, mRenderAlpha);
					break;

				default:
					l = l.next;
			}
		}

		if (t.fadeTime == 0)
			t.fadeTime = getMinFade(t);

		if (debugOverdraw) {
			if (t.zoomLevel > pos.zoomLevel)
				PolygonLayer.Renderer.drawOver(m, Color.BLUE, 0.5f);
			else if (t.zoomLevel < pos.zoomLevel)
				PolygonLayer.Renderer.drawOver(m, Color.RED, 0.5f);
			else
				PolygonLayer.Renderer.drawOver(m, Color.GREEN, 0.5f);

			return;
		}

		if (mRenderOverdraw != 0 && MapRenderer.frametime - t.fadeTime < 500) {
			float fade = 1 - (MapRenderer.frametime - t.fadeTime) / 500f;
			PolygonLayer.Renderer.drawOver(m, mRenderOverdraw, fade * fade);
			MapRenderer.animate();
		} else {
			PolygonLayer.Renderer.drawOver(m, 0, 1);
		}
	}

	private int drawProxyChild(MapTile tile, MapPosition pos) {
		int drawn = 0;
		for (int i = 0; i < 4; i++) {
			if ((tile.proxies & 1 << i) == 0)
				continue;

			MapTile c = tile.node.child(i);

			if (c.state == STATE_READY) {
				drawTile(c, pos);
				drawn++;
			}
		}
		return drawn;
	}

	private void drawProxyTile(MapTile tile, MapPosition pos, boolean parent,
	        boolean preferParent) {

		Node<MapTile> r = tile.node;
		MapTile proxy;

		if (!preferParent) {
			// prefer drawing children
			if (drawProxyChild(tile, pos) == 4)
				return;

			if (parent) {
				// draw parent proxy
				if ((tile.proxies & MapTile.PROXY_PARENT) != 0) {
					proxy = r.parent.item;
					if (proxy.state == STATE_READY) {
						//log.debug("1. draw parent " + proxy);
						drawTile(proxy, pos);
					}
				}
			} else if ((tile.proxies & MapTile.PROXY_GRAMPA) != 0) {
				// check if parent was already drawn
				if ((tile.proxies & MapTile.PROXY_PARENT) != 0) {
					proxy = r.parent.item;
					if (proxy.state == STATE_READY)
						return;
				}

				proxy = r.parent.parent.item;
				if (proxy.state == STATE_READY)
					drawTile(proxy, pos);
			}
		} else {
			// prefer drawing parent
			if (parent) {
				if ((tile.proxies & MapTile.PROXY_PARENT) != 0) {
					proxy = r.parent.item;
					if (proxy != null && proxy.state == STATE_READY) {
						//log.debug("2. draw parent " + proxy);
						drawTile(proxy, pos);
						return;

					}
				}
				drawProxyChild(tile, pos);

			} else if ((tile.proxies & MapTile.PROXY_GRAMPA) != 0) {
				// check if parent was already drawn
				if ((tile.proxies & MapTile.PROXY_PARENT) != 0) {
					proxy = r.parent.item;
					if (proxy.state == STATE_READY)
						return;
				}
				// this will do nothing, just to check
				if (drawProxyChild(tile, pos) > 0)
					return;

				proxy = r.parent.parent.item;
				if (proxy.state == STATE_READY)
					drawTile(proxy, pos);
			}
		}
	}

	@Override
	protected void render(MapPosition position, Matrices matrices) {
	}
}
