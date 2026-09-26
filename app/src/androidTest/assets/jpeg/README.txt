Synthetic codec fixtures generated with ImageMagick (no external photographs):

magick -size 64x48 gradient: -colorspace Gray -quality 95 grayscale.jpg
magick -size 64x48 xc:red -colorspace CMYK -quality 95 cmyk.jpg

The first exercises one-component input. The second exercises safe preservation
of a four-component JPEG without silently changing its color interpretation.
