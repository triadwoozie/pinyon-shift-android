---
title: Game flow
section: Reverse engineering
order: 5
description: How Forza Horizon's flow from boot to free roam and races is written as XML state graphs in gamemodes.zip, how each Behaviour names a States:: C++ class, the script actions found by name, and what title update 4 adds for Rally.
---

# Game flow

Most of what the game does between screens is not in code: it is in about fifty XML **state graphs** in `media/gamemodes.zip`. Starting the game, free roam, every kind of race, the pause menu, the map, photo shoots and the multiplayer lobby are each a `StateGroup` of `State` elements. A state runs **behaviours**, can call into a state of another group, and leaves through named exits to the next state.

The executable supplies the pieces. Every behaviour id in the scripts is the name of a C++ class in the `States` namespace, which the executable registers by name. So the scripts can recombine existing behaviours freely, but anything new needs code.

{{diagram:game-flow|How a flow file reaches the code. A state's behaviours are created by class name; a Node runs a state of another group (most often the shared global group) and comes back through its exits.}}

The shape of a flow file, with invented ids (the element and attribute names are the game's):

```xml
<StateGroup id="example_event">
  <State id="intro">
    <Behaviour id="CSetTimeOfDay">            <!-- a States:: class, created by name -->
      <Attribute id="..." value="..."/>       <!-- parameters the class reads -->
    </Behaviour>
    <Node id="take_control" state="global.some_state">
      <Exit id="success" target="race"/>     <!-- where to go when the called state exits -->
      <Exit id="failure" target="leave"/>
    </Node>
  </State>
  <State id="race"> ... </State>
  <State id="leave"> ... </State>
</StateGroup>
```

Behaviours may also hold `Transition` elements, and a state may hold `Entry` and `StateSubstitute` elements; their exact semantics are not traced yet. `gamemodes.zip` also holds documents that are not state graphs: the activity list, cutscene cameras, leaderboards and radio DJ requests (see the records below).

Graphing a flow is the quickest way to read one. This prints a [Graphviz](https://graphviz.org/) graph of an extracted flow file, with calls into other groups dashed:

```python
import sys
import xml.etree.ElementTree as ET


def flow_to_dot(path):
    """A Graphviz graph of one extracted StateGroup file."""
    group = ET.parse(path).getroot()
    lines = [f'digraph "{group.get("id")}" {{', "  node [shape=box, fontname=monospace];"]
    for state in group.findall("State"):
        sid = state.get("id")
        behaviours = [b.get("id") for b in state.findall("Behaviour")]
        label = "\\n".join([sid, *behaviours])
        lines.append(f'  "{sid}" [label="{label}"];')
        for node in state.findall("Node"):  # runs a state of another group
            lines.append(f'  "{sid}" -> "{node.get("state")}" [style=dashed];')
        for leave in state.iter("Exit"):
            if leave.get("target"):
                lines.append(f'  "{sid}" -> "{leave.get("target")}" [label="{leave.get("id")}"];')
    return "\n".join(lines + ["}"])


print(flow_to_dot(sys.argv[1]))
```

```sh
python flow_to_dot.py extracted/gamemodes/<file>.xml | dot -Tsvg -o flow.svg
```

Extract the archive first ([Methods and tools](game-methods.md#data-files)); the files are plain XML once out.

## Flow scripts

{{findings:flow/Flow scripts}}

## Script actions

Scripts also call **actions**: small functors registered under a name, separate from the state behaviours. Each one's factory has a create method in its vtable, which leads from the name to the code.

{{findings:flow/Script actions}}

## Rally behaviours (v4)

Title update 4 adds the Rally expansion's flow: new state groups in its `media.zip` and 335 new `States` classes ([Title update v4](game-title-update.md)).

{{findings:flow/Rally behaviours (v4)}}
