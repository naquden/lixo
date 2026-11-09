"""Maps `EquipmentSlot.id` to its 8 [StyleTemplate]s, and expands a `"<slot_id>:<idx>"` ref."""
from __future__ import annotations

from typing import Dict, List

from tools.content_pipeline.pack.templates.common import StyleTemplate
from tools.content_pipeline.pack.templates.slots import (
    arms_gloves,
    armor_shirt,
    hand_left,
    hand_right,
    hat,
    pants,
    shoes,
    shoulders_back,
    tail,
)

SLOT_TEMPLATES: Dict[str, List[StyleTemplate]] = {
    "hat": hat.STYLES,
    "shoulders_back": shoulders_back.STYLES,
    "arms_gloves": arms_gloves.STYLES,
    "armor_shirt": armor_shirt.STYLES,
    "pants": pants.STYLES,
    "shoes": shoes.STYLES,
    "hand_right": hand_right.STYLES,
    "hand_left": hand_left.STYLES,
    "tail": tail.STYLES,
}

# A slot's extra item-level keys, applied by build_pack.py to every item of that slot.
SLOT_HIDES_PARTS: Dict[str, List[str]] = {
    "pants": ["pants"],
    "shoes": ["shoes"],
    "tail": ["tail"],
}


def expand(template_ref: str) -> StyleTemplate:
    """`template_ref` is `"<slot_id>:<style_index>"`, e.g. `"hat:5"`."""
    slot_id, idx_str = template_ref.split(":", 1)
    styles = SLOT_TEMPLATES[slot_id]
    return styles[int(idx_str)]
