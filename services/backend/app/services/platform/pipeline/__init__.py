"""Per-feature modules composed by `PipelineServices`.

`pipeline_services.py` keeps `PipelineServices` as a thin facade that
subclasses the mixins defined here; every existing import and call site is
unchanged. Splitting the former 3,197-line god module this way makes the
migration's authors->Go and feed->Go phases carve disjoint files.
"""
