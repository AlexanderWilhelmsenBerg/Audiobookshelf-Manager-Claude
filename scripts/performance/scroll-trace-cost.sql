-- LIB-002 / PRODUCT_SPEC 17.3: category totals overlap; never sum them as frame cost.
-- Only BookWave main/RenderThread technical categories are emitted; no private trace strings.
WITH operations AS (
  SELECT s.dur,
    CASE
      WHEN t.tid=p.pid AND s.name GLOB 'Choreographer#doFrame*' THEN 'main_frame'
      WHEN t.tid=p.pid AND s.name='Record View#draw()' THEN 'main_record_draw'
      WHEN t.tid=p.pid AND s.name='Compose:recompose' THEN 'main_recompose'
      WHEN t.tid=p.pid AND s.name='HazeEffectNode-getOrCreateRenderEffect' THEN 'main_haze_effect_lookup'
      WHEN t.name='RenderThread' AND s.name GLOB 'DrawFrames*' THEN 'render_frame'
      WHEN t.name='RenderThread' AND s.name='flush commands' THEN 'render_flush_commands'
      WHEN t.name='RenderThread' AND s.name='flush layers' THEN 'render_flush_layers'
      WHEN t.name='RenderThread' AND s.name='FillRectOp' THEN 'render_fill_rect'
      WHEN t.name='RenderThread' AND s.name='AtlasTextOp' THEN 'render_text'
      WHEN t.name='RenderThread' AND s.name='TextureOp' THEN 'render_texture'
    END AS category
  FROM slice s
  JOIN thread_track tr ON s.track_id=tr.id
  JOIN thread t USING(utid)
  JOIN process p USING(upid)
  WHERE p.name='org.homebord.bookwave' AND s.dur>0
)
SELECT category, count(*) AS count, round(sum(dur)/1e6,3) AS inclusive_ms,
       round(max(dur)/1e6,3) AS max_ms
FROM operations WHERE category IS NOT NULL GROUP BY category ORDER BY category;
