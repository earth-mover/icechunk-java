# Example: segment nuclei with StarDist

This example opens a 3D microscopy image from an icechunk repository on [Arraylake](https://earthmover.io), takes
20 slices of its nuclear stain, and segments the nuclei with [StarDist](https://github.com/stardist/stardist), a deep
learning model that runs in Python.

Fiji runs it through [Appose](https://github.com/apposed/appose), which lets Fiji use Python deep learning models
without leaving Fiji. Appose builds a Python environment from the packages a script names, runs the script in a
separate worker process, and passes images between Fiji and Python through shared memory instead of copying them. A
script in the Script Editor that starts with `#@script (language="appose-python")` runs this way, so nothing is
installed by hand beyond the Fiji setup. The [Appose workshop](https://fiji.github.io/i2k-2025-appose/) covers writing
such scripts.

The image is `idr0062A` from Earthmover's demo copy of the
[Image Data Resource](https://idr.openmicroscopy.org) (IDR). It comes from
[idr0062](https://github.com/IDR/idr0062-blin-nuclearsegmentation), a study of nuclear segmentation in mouse tissue
imaged by confocal microscopy, and has two channels, LaminB1 and DAPI, at 271 × 275 pixels and 236 slices.

## What you need

- Fiji set up as in [Use in Fiji](fiji.md), with the icechunk-arraylake-java jar added, since the image is on
  Arraylake.
- An Arraylake login. The repository is readable by any signed-in Arraylake user. The jar uses the login that the
  Python client writes, so run `arraylake auth login` once with the
  [Arraylake Python client](https://docs.earthmover.io).
- Appose Python scripting, which ships with Fiji on the Fiji-Latest update site. The Script Editor lists it under
  **Templates > Appose**.

## Open the image

1. Choose **File > Import > HDF5/N5/Zarr/OME-NGFF ...**.
2. Paste this URL into the dataset field:

    ```text
    al:earthmover-demos/IDR|icechunk://branch.main/idr0062A
    ```

3. Click **Detect datasets**. The tree lists three resolution levels, `0` to `2`, and a `labels` group.
4. Select `0`, the full-resolution array, tick **Open as virtual**, and click **OK**.

![The Open N5 dialog with the Arraylake URL entered, the detected tree showing arrays 0, 1 and 2 and a labels group,
array 0 selected, and Open as virtual ticked.](images/stardist/open-n5.png)

The image opens as a 2-channel stack of 236 slices. With **Open as virtual**, Fiji reads a slice from Arraylake only
when it is shown, so the window appears in a few seconds. The data is dim against the full 16-bit range and can look
black at first: choose **Process > Enhance Contrast** with the default 0.35% saturated pixels.

![Slice 118 of the DAPI channel after Enhance Contrast: about twenty nuclei around the edge of a round embryo, shown in
yellow on black.](images/stardist/opened.png)

## Take the DAPI slices

StarDist's fluorescence model expects one nuclear-stain channel, and the script segments one slice at a time. Choose
**Image > Duplicate...** and set:

| Field | Value |
|---|---|
| Title | `idr0062A DAPI` |
| Channels (c) | `2` |
| Slices (z) | `110-129` |

Fiji reads those 20 slices of the DAPI channel from Arraylake and opens them as an ordinary in-memory stack. Close the
virtual image if you no longer need it, and run **Process > Enhance Contrast** on the new stack.

![The duplicated stack, 20 slices of the DAPI channel, with nuclei visible after Enhance
Contrast.](images/stardist/dapi.png)

## Run StarDist

1. Open the Script Editor (**File > New > Script...**) and choose **Templates > Appose > StarDist cellcast**.
2. With the `idr0062A DAPI` window active, click **Run**. The script takes the active image as its input and asks for
   its parameters. The defaults suit this image.

    ![The script's parameter dialog: Pmin 1, Pmax 99.8, Prob_threshold 0.479, Nms_threshold 0.3, and Gpu
    ticked.](images/stardist/parameters.png)

3. Click **OK**.

The first run downloads and installs a Python environment with [cellcast](https://pypi.org/project/cellcast/), which
provides StarDist, before it segments anything. Later runs reuse the environment and finish the 20 slices in a few
seconds. The result opens as a `labels` stack of the same size, with each nucleus a different integer. To colour the
labels, choose **Image > Lookup Tables > glasbey on dark**.

![The labels stack for slice 9 coloured with the glasbey on dark lookup table: each nucleus from the DAPI image is a
filled region of its own colour.](images/stardist/labels.png)

The template is
[`StarDist_cellcast.py`](https://github.com/scijava/scripting-appose-python/blob/main/src/main/resources/script_templates/Appose/StarDist_cellcast.py)
from [scripting-appose-python](https://github.com/scijava/scripting-appose-python). Its header names the Python
packages it needs, and Appose builds the environment from that header.
