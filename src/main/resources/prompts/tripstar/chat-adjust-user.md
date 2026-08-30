User request:
{{message}}

Recent conversation (at most eight compact turns):
{{chat_history}}

Resolved user memory (already filtered by server precedence; never override this round's explicit request):
{{memory_context}}

Current trip snapshot (baseline for patch paths):
{{trip_plan}}

RAG evidence (use only as supporting context; map facts win when they conflict):
{{rag_context}}

Return only a JSON object matching this schema. Do not return a full trip_plan.
- reply: concise user-facing answer
- change_summary: concise description of the change
- operations: RFC 6902 JSON Patch operations against the current trip snapshot
- need_route_recalculate: true only when route or travel time must be recalculated

Only include operations required by the request. Preserve every field not targeted by the user.
Use the original zero-based indexes under /days in patch paths. For questions, return an empty operations array.
Do not invent opening hours, prices, coordinates, or other facts that are not in the supplied evidence.
{{format}}
