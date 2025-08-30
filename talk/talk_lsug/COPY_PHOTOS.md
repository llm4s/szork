# Copy Commands for Speaker Photos

## Instructions

The speaker photos have been provided. Please save them from the chat and use these commands to copy them to the correct locations:

### For Kannupriya's Photo (Image #2 in the chat)
1. Save the first image (Kannupriya speaking at podium) to your Downloads or Desktop
2. Run this command:
```bash
cp [path/where/you/saved/kannupriya.jpg] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/founder2.jpg
```

### For Rory's Photo (Image #4 in the chat)
1. Save the second image (Rory outdoors) to your Downloads or Desktop  
2. Run this command:
```bash
cp [path/where/you/saved/rory.jpg] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/founder1.jpg
```

## Example Commands

If you saved them to Downloads:
```bash
# Copy Kannupriya's photo
cp ~/Downloads/kannupriya.jpg /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/founder2.jpg

# Copy Rory's photo
cp ~/Downloads/rory.jpg /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/founder1.jpg
```

## Verify Photos Are In Place
```bash
# Check that both photos exist
ls -la /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/

# You should see:
# founder1.jpg (Rory's photo)
# founder2.jpg (Kannupriya's photo)
```

## Note on Photo Order
- founder1.jpg = Rory Graves (first speaker)
- founder2.jpg = Kannupriya Kalra (second speaker)

The presentation will now correctly reference these photos in the speaker slides.