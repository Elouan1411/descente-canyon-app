# Regulation audit working files

- Active regulations in embedded dataset: 241
- With attachments mirrored on descente-canyon: 125
- With effective date: 185

Files generated:
- `active_regulations_inventory.csv`: full inventory of currently active regulations from embedded data
- `missing_regulations_candidates.md`: candidate new/updated regulations to validate manually

Notes:
- `sourceConfidence=official_link_present`: a direct official/government URL was detected in source fields
- `sourceConfidence=mirror_attachment_present`: descente-canyon stores a mirrored attachment but not an official live link
- `sourceConfidence=needs_manual_search`: no obvious official source URL is currently embedded