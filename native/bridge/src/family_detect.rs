use std::collections::HashMap;

/// Size of the DCT block used for fingerprinting
const DCT_SIZE: usize = 8;

/// Maximum alpha value for fully opaque pixels
const FULLY_OPAQUE_ALPHA: u8 = 255;

/// Number of color channels in RGBA
const CHANNELS: usize = 4;

/// Downsample an image by a factor of 2 using box filter averaging.
///
/// # Arguments
/// * `pixels` - RGBA pixel data
/// * `width` - Image width in pixels
/// * `height` - Image height in pixels
///
/// # Returns
/// A tuple containing (downsampled_pixels, new_width, new_height)
#[inline]
pub fn downsample_box_filter(pixels: &[u8], width: u32, height: u32) -> (Vec<u8>, u32, u32) {
    let width = width.max(1) as usize;
    let height = height.max(1) as usize;
    let out_width = (width / 2).max(1);
    let out_height = (height / 2).max(1);

    let mut output = vec![0u8; out_width * out_height * CHANNELS];

    for out_y in 0..out_height {
        let y0 = (out_y * 2).min(height - 1);
        let y1 = (out_y * 2 + 1).min(height - 1);

        for out_x in 0..out_width {
            let x0 = (out_x * 2).min(width - 1);
            let x1 = (out_x * 2 + 1).min(width - 1);

            let p00 = texel_at(pixels, width, x0, y0);
            let p10 = texel_at(pixels, width, x1, y0);
            let p01 = texel_at(pixels, width, x0, y1);
            let p11 = texel_at(pixels, width, x1, y1);

            let out_offset = (out_y * out_width + out_x) * CHANNELS;

            for channel in 0..CHANNELS {
                let sum = p00[channel] as u32
                    + p10[channel] as u32
                    + p01[channel] as u32
                    + p11[channel] as u32;
                output[out_offset + channel] = ((sum + 2) / 4) as u8;
            }
        }
    }

    (output, out_width as u32, out_height as u32)
}

/// Safely retrieve a texel from pixel data, returning [0,0,0,0] if out of bounds.
#[inline]
fn texel_at(pixels: &[u8], width: usize, x: usize, y: usize) -> [u8; CHANNELS] {
    let offset = (y * width + x) * CHANNELS;
    if offset + CHANNELS > pixels.len() {
        return [0, 0, 0, 0];
    }
    [
        pixels[offset],
        pixels[offset + 1],
        pixels[offset + 2],
        pixels[offset + 3],
    ]
}

/// Build a mipmap chain by repeatedly downsampling.
///
/// # Arguments
/// * `pixels` - Source RGBA pixel data
/// * `width` - Source image width
/// * `height` - Source image height
/// * `max_level` - Maximum number of mip levels to generate
///
/// # Returns
/// A vector of (pixels, width, height) tuples for each mip level
pub fn build_mip_chain(
    pixels: &[u8],
    width: u32,
    height: u32,
    max_level: u32,
) -> Vec<(Vec<u8>, u32, u32)> {
    let mut chain: Vec<(Vec<u8>, u32, u32)> = Vec::with_capacity(max_level as usize + 1);
    chain.push((pixels.to_vec(), width, height));

    for _ in 0..max_level {
        let (prev_pixels, prev_width, prev_height) = chain
            .last()
            .expect("chain always has a base level");

        if *prev_width <= 1 && *prev_height <= 1 {
            break;
        }

        let next = downsample_box_filter(prev_pixels, *prev_width, *prev_height);
        chain.push(next);
    }

    chain
}

/// Metadata for a sprite within a texture atlas.
#[derive(Clone, Copy, Debug)]
pub struct SpriteMeta {
    pub src_offset: usize,
    pub width: u32,
    pub height: u32,
    pub dest_x: u32,
    pub dest_y: u32,
    pub tinted: bool,
}

/// Classification of alpha channel behavior.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AlphaShape {
    FullyOpaque,
    PunchThrough,
    Blended,
}

/// Classify the alpha shape of a sprite region.
///
/// # Arguments
/// * `rgba8` - RGBA pixel data
/// * `src_offset` - Starting offset of the sprite
/// * `width` - Sprite width
/// * `height` - Sprite height
///
/// # Returns
/// AlphaShape classification
pub fn classify_alpha_shape(
    rgba8: &[u8],
    src_offset: usize,
    width: u32,
    height: u32,
) -> AlphaShape {
    let width = width as usize;
    let height = height as usize;
    let mut saw_transparent = false;
    let mut saw_opaque = false;

    for y in 0..height {
        let row_start = src_offset + y * width * CHANNELS;
        let row_end = row_start + width * CHANNELS;

        if row_end > rgba8.len() {
            break;
        }

        let row = &rgba8[row_start..row_end];
        for texel in row.chunks_exact(CHANNELS) {
            let alpha = texel[3];
            if alpha == 0 {
                saw_transparent = true;
            } else if alpha == FULLY_OPAQUE_ALPHA {
                saw_opaque = true;
            } else {
                return AlphaShape::Blended;
            }
        }
    }

    match (saw_transparent, saw_opaque) {
        (true, true) | (true, false) => AlphaShape::PunchThrough,
        (false, true) => AlphaShape::FullyOpaque,
        (false, false) => AlphaShape::FullyOpaque, // Empty sprite treated as opaque
    }
}

/// Batch classify alpha shapes for multiple sprites.
pub fn classify_alpha_shape_batch(pixels: &[u8], sprites: &[SpriteMeta]) -> Vec<AlphaShape> {
    sprites
        .iter()
        .map(|s| classify_alpha_shape(pixels, s.src_offset, s.width, s.height))
        .collect()
}

/// Check if a sprite has any alpha (non-opaque pixels).
pub fn classify_alpha(rgba8: &[u8], src_offset: usize, width: u32, height: u32) -> bool {
    classify_alpha_shape(rgba8, src_offset, width, height) != AlphaShape::FullyOpaque
}

/// Batch classify alpha presence for multiple sprites.
pub fn classify_alpha_batch(pixels: &[u8], sprites: &[SpriteMeta]) -> Vec<bool> {
    sprites
        .iter()
        .map(|s| classify_alpha(pixels, s.src_offset, s.width, s.height))
        .collect()
}

/// A family of similar sprites.
#[derive(Clone, Debug)]
pub struct Family {
    pub representative_index: usize,
    pub member_indices: Vec<usize>,
}

/// Complete result from family detection and atlas assembly.
pub struct FamilyResult {
    pub fingerprints: Vec<u64>,
    pub families: Vec<Family>,
    pub atlas_buffer: Vec<u8>,
    pub alpha_flags: Vec<bool>,
    pub alpha_shapes: Vec<AlphaShape>,
}

/// Generate the cosine table for DCT computation.
#[inline]
fn cosine_table() -> [[f64; DCT_SIZE]; DCT_SIZE] {
    let mut table = [[0.0f64; DCT_SIZE]; DCT_SIZE];
    for x in 0..DCT_SIZE {
        for u in 0..DCT_SIZE {
            table[x][u] = (((2.0 * x as f64 + 1.0) * u as f64 * std::f64::consts::PI)
                / (2.0 * DCT_SIZE as f64))
                .cos();
        }
    }
    table
}

/// Downsample a sprite region to DCT_SIZE x DCT_SIZE luminance values.
#[inline]
fn downsample_to_luminance(
    rgba8: &[u8],
    src_offset: usize,
    width: u32,
    height: u32,
) -> [[f64; DCT_SIZE]; DCT_SIZE] {
    let mut luminance = [[0.0f64; DCT_SIZE]; DCT_SIZE];
    let width = width as usize;
    let height = height as usize;

    for by in 0..DCT_SIZE {
        for bx in 0..DCT_SIZE {
            let start_x = (bx * width) / DCT_SIZE;
            let end_x = ((bx + 1) * width / DCT_SIZE).max(start_x + 1);
            let start_y = (by * height) / DCT_SIZE;
            let end_y = ((by + 1) * height / DCT_SIZE).max(start_y + 1);

            let mut sum = 0.0f64;
            let mut samples = 0u32;

            for y in start_y..end_y.min(height) {
                for x in start_x..end_x.min(width) {
                    let offset = src_offset + (y * width + x) * CHANNELS;
                    if offset + 3 >= rgba8.len() {
                        continue;
                    }
                    let r = rgba8[offset] as f64;
                    let g = rgba8[offset + 1] as f64;
                    let b = rgba8[offset + 2] as f64;
                    sum += 0.299 * r + 0.587 * g + 0.114 * b;
                    samples += 1;
                }
            }

            luminance[by][bx] = if samples == 0 {
                0.0
            } else {
                sum / samples as f64
            };
        }
    }

    luminance
}

/// Perform forward DCT on a block.
#[inline]
fn forward_dct(
    block: &[[f64; DCT_SIZE]; DCT_SIZE],
    cosine: &[[f64; DCT_SIZE]; DCT_SIZE],
) -> [[f64; DCT_SIZE]; DCT_SIZE] {
    let mut result = [[0.0f64; DCT_SIZE]; DCT_SIZE];

    for u in 0..DCT_SIZE {
        for v in 0..DCT_SIZE {
            let mut sum = 0.0f64;
            for x in 0..DCT_SIZE {
                for y in 0..DCT_SIZE {
                    sum += block[y][x] * cosine[x][u] * cosine[y][v];
                }
            }
            let cu = if u == 0 { 1.0 / std::f64::consts::SQRT_2 } else { 1.0 };
            let cv = if v == 0 { 1.0 / std::f64::consts::SQRT_2 } else { 1.0 };
            result[v][u] = 0.25 * cu * cv * sum;
        }
    }

    result
}

/// Calculate the median of a slice of f64 values.
///
/// Uses total_cmp to handle NaN values gracefully without panicking.
#[inline]
fn median(values: &mut [f64]) -> f64 {
    values.sort_by(|a, b| a.total_cmp(b));
    let mid = values.len() / 2;
    if values.len() % 2 == 0 {
        (values[mid - 1] + values[mid]) / 2.0
    } else {
        values[mid]
    }
}

/// Generate a perceptual fingerprint for a sprite using DCT.
#[inline]
fn fingerprint(
    rgba8: &[u8],
    src_offset: usize,
    width: u32,
    height: u32,
    cosine: &[[f64; DCT_SIZE]; DCT_SIZE],
) -> u64 {
    let luminance = downsample_to_luminance(rgba8, src_offset, width, height);
    let dct = forward_dct(&luminance, cosine);

    let mut low_frequency = Vec::with_capacity(DCT_SIZE * DCT_SIZE - 1);
    for y in 0..DCT_SIZE {
        for x in 0..DCT_SIZE {
            if x == 0 && y == 0 {
                continue;
            }
            low_frequency.push(dct[y][x]);
        }
    }

    let med = median(&mut low_frequency);
    let mut fp: u64 = 0;
    let mut bit = 0u32;

    for y in 0..DCT_SIZE {
        for x in 0..DCT_SIZE {
            if x == 0 && y == 0 {
                continue;
            }
            if dct[y][x] > med {
                fp |= 1u64 << bit;
            }
            bit += 1;
        }
    }

    fp
}

/// Group sprites by similarity using Hamming distance.
fn group_by_similarity(
    fingerprints: &[u64],
    tinted: &[bool],
    max_hamming_distance: u32,
) -> Vec<Family> {
    let count = fingerprints.len();
    let mut by_tint: HashMap<bool, Vec<usize>> = HashMap::new();
    by_tint.insert(true, Vec::new());
    by_tint.insert(false, Vec::new());

    for i in 0..count {
        by_tint.get_mut(&tinted[i])
            .expect("tint buckets initialized")
            .push(i);
    }

    let mut visited = vec![false; count];
    let mut families = Vec::new();

    for i in 0..count {
        if visited[i] {
            continue;
        }

        visited[i] = true;
        let mut members = Vec::new();
        let mut frontier = vec![i];

        while let Some(current) = frontier.pop() {
            let bucket = by_tint.get(&tinted[current])
                .expect("tint bucket exists");

            for &j in bucket {
                if visited[j] {
                    continue;
                }
                let xor = fingerprints[current] ^ fingerprints[j];
                if xor.count_ones() <= max_hamming_distance {
                    visited[j] = true;
                    members.push(j);
                    frontier.push(j);
                }
            }
        }

        families.push(Family {
            representative_index: i,
            member_indices: members,
        });
    }

    families
}

/// Assemble sprites into a texture atlas.
fn assemble_atlas(
    pixels: &[u8],
    sprites: &[SpriteMeta],
    atlas_width: u32,
    atlas_height: u32,
) -> Vec<u8> {
    let atlas_width = atlas_width as usize;
    let atlas_height = atlas_height as usize;
    let mut atlas = vec![0u8; atlas_width * atlas_height * CHANNELS];

    for sprite in sprites {
        let sprite_width = sprite.width as usize;
        let sprite_height = sprite.height as usize;
        let dest_x = sprite.dest_x as usize;
        let dest_y = sprite.dest_y as usize;

        for y in 0..sprite_height {
            let src_row_start = sprite.src_offset + y * sprite_width * CHANNELS;
            let src_row_end = src_row_start + sprite_width * CHANNELS;

            if src_row_end > pixels.len() {
                continue;
            }

            let dest_row = dest_y + y;
            if dest_row >= atlas_height {
                continue;
            }

            let dest_row_start = (dest_row * atlas_width + dest_x) * CHANNELS;
            let dest_row_end = dest_row_start + sprite_width * CHANNELS;

            if dest_x + sprite_width > atlas_width || dest_row_end > atlas.len() {
                continue;
            }

            atlas[dest_row_start..dest_row_end]
                .copy_from_slice(&pixels[src_row_start..src_row_end]);
        }
    }

    atlas
}

/// Main entry point for sprite family detection and atlas assembly.
///
/// # Arguments
/// * `pixels` - RGBA pixel data containing all sprites
/// * `sprites` - Metadata for each sprite
/// * `atlas_width` - Width of the output atlas
/// * `atlas_height` - Height of the output atlas
/// * `max_hamming_distance` - Maximum Hamming distance for family grouping
///
/// # Returns
/// A FamilyResult containing fingerprints, families, and atlas data
pub fn detect_families_and_assemble(
    pixels: &[u8],
    sprites: &[SpriteMeta],
    atlas_width: u32,
    atlas_height: u32,
    max_hamming_distance: u32,
) -> FamilyResult {
    let cosine = cosine_table();

    let fingerprints: Vec<u64> = sprites
        .iter()
        .map(|s| fingerprint(pixels, s.src_offset, s.width, s.height, &cosine))
        .collect();

    let tinted: Vec<bool> = sprites.iter().map(|s| s.tinted).collect();
    let families = group_by_similarity(&fingerprints, &tinted, max_hamming_distance);
    let atlas_buffer = assemble_atlas(pixels, sprites, atlas_width, atlas_height);
    let alpha_flags = classify_alpha_batch(pixels, sprites);
    let alpha_shapes = classify_alpha_shape_batch(pixels, sprites);

    FamilyResult {
        fingerprints,
        families,
        atlas_buffer,
        alpha_flags,
        alpha_shapes,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_WIDTH: u32 = 16;
    const TEST_HEIGHT: u32 = 16;

    /// Create a test image with two duplicated sprites side by side.
    fn duplicated_test_image(width: u32, height: u32) -> (Vec<u8>, usize) {
        let pixel_count = (width * height) as usize;
        let mut pixels = vec![0u8; pixel_count * CHANNELS * 2];
        for i in 0..pixel_count {
            let v = ((i * 37) % 255) as u8;
            let offset = i * CHANNELS;
            pixels[offset] = v;
            pixels[offset + 1] = v;
            pixels[offset + 2] = v;
            pixels[offset + 3] = FULLY_OPAQUE_ALPHA;
        }
        let second_offset = pixel_count * CHANNELS;
        pixels.copy_within(0..second_offset, second_offset);
        (pixels, second_offset)
    }

    /// Create sprite metadata for side-by-side sprites.
    fn side_by_side_sprites(
        width: u32,
        height: u32,
        second_offset: usize,
        second_tinted: bool,
    ) -> Vec<SpriteMeta> {
        vec![
            SpriteMeta {
                src_offset: 0,
                width,
                height,
                dest_x: 0,
                dest_y: 0,
                tinted: false,
            },
            SpriteMeta {
                src_offset: second_offset,
                width,
                height,
                dest_x: width,
                dest_y: 0,
                tinted: second_tinted,
            },
        ]
    }

    #[test]
    fn identical_sprites_produce_identical_fingerprints() {
        let (pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, false);
        let result = detect_families_and_assemble(&pixels, &sprites, TEST_WIDTH * 2, TEST_HEIGHT, 4);

        assert_eq!(result.fingerprints[0], result.fingerprints[1]);
        assert_eq!(
            result.atlas_buffer.len(),
            (TEST_WIDTH * 2 * TEST_HEIGHT) as usize * CHANNELS
        );
    }

    #[test]
    fn identical_sprites_are_grouped_into_one_family() {
        let (pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, false);
        let result = detect_families_and_assemble(&pixels, &sprites, TEST_WIDTH * 2, TEST_HEIGHT, 4);

        assert_eq!(result.families.len(), 1);
        assert_eq!(result.families[0].member_indices, vec![1]);
        assert_eq!(result.families[0].representative_index, 0);
    }

    #[test]
    fn differing_tint_prevents_grouping_even_when_identical() {
        let (pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, true);
        let result = detect_families_and_assemble(&pixels, &sprites, TEST_WIDTH * 2, TEST_HEIGHT, 4);

        assert_eq!(result.families.len(), 2);
        assert!(result.families.iter().all(|f| f.member_indices.is_empty()));
    }

    /// Create a solid color RGBA buffer.
    fn solid_rgba(width: u32, height: u32, r: u8, g: u8, b: u8, a: u8) -> Vec<u8> {
        let pixel_count = (width * height) as usize;
        let mut pixels = vec![0u8; pixel_count * CHANNELS];
        for chunk in pixels.chunks_exact_mut(CHANNELS) {
            chunk[0] = r;
            chunk[1] = g;
            chunk[2] = b;
            chunk[3] = a;
        }
        pixels
    }

    #[test]
    fn fully_opaque_sprite_is_not_alpha() {
        let pixels = solid_rgba(TEST_WIDTH, TEST_HEIGHT, 200, 100, 50, 255);
        assert!(!classify_alpha(&pixels, 0, TEST_WIDTH, TEST_HEIGHT));
    }

    #[test]
    fn fully_transparent_sprite_is_alpha() {
        let pixels = solid_rgba(TEST_WIDTH, TEST_HEIGHT, 200, 100, 50, 0);
        assert!(classify_alpha(&pixels, 0, TEST_WIDTH, TEST_HEIGHT));
    }

    #[test]
    fn single_non_opaque_texel_is_sufficient_to_classify_as_alpha() {
        let mut pixels = solid_rgba(TEST_WIDTH, TEST_HEIGHT, 10, 20, 30, 255);
        let last = pixels.len() - 1;
        pixels[last] = 254;
        assert!(classify_alpha(&pixels, 0, TEST_WIDTH, TEST_HEIGHT));
    }

    #[test]
    fn near_opaque_255_elsewhere_does_not_misclassify_as_opaque() {
        let mut pixels = solid_rgba(4, 4, 5, 5, 5, 255);
        pixels[3] = 128;
        assert!(classify_alpha(&pixels, 0, 4, 4));
    }

    #[test]
    fn classify_alpha_respects_src_offset_for_second_sprite_in_shared_buffer() {
        let (mut pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        pixels[second_offset + 3] = 0;
        assert!(!classify_alpha(&pixels, 0, TEST_WIDTH, TEST_HEIGHT));
        assert!(classify_alpha(&pixels, second_offset, TEST_WIDTH, TEST_HEIGHT));
    }

    #[test]
    fn classify_alpha_out_of_bounds_geometry_does_not_panic() {
        let pixels = vec![0u8; CHANNELS];
        assert!(!classify_alpha(&pixels, 0, 10, 10));
    }

    #[test]
    fn classify_alpha_batch_matches_per_sprite_indices() {
        let (mut pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        pixels[second_offset + 3] = 0;
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, false);
        let flags = classify_alpha_batch(&pixels, &sprites);
        assert_eq!(flags, vec![false, true]);
    }

    #[test]
    fn detect_families_and_assemble_populates_alpha_flags_in_input_order() {
        let (mut pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        pixels[second_offset + 3] = 0;
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, false);
        let result = detect_families_and_assemble(&pixels, &sprites, TEST_WIDTH * 2, TEST_HEIGHT, 4);
        assert_eq!(result.alpha_flags, vec![false, true]);
    }

    #[test]
    fn downsample_halves_even_dimensions() {
        let pixels = solid_rgba(8, 8, 100, 150, 200, 255);
        let (out, w, h) = downsample_box_filter(&pixels, 8, 8);
        assert_eq!((w, h), (4, 4));
        assert_eq!(out.len(), 4 * 4 * CHANNELS);
    }

    #[test]
    fn downsample_of_solid_color_stays_the_same_color() {
        let pixels = solid_rgba(16, 16, 37, 91, 214, 255);
        let (out, w, h) = downsample_box_filter(&pixels, 16, 16);
        assert_eq!((w, h), (8, 8));
        for texel in out.chunks_exact(CHANNELS) {
            assert_eq!(texel, &[37, 91, 214, 255]);
        }
    }

    #[test]
    fn downsample_averages_a_2x2_checkerboard_to_mid_gray() {
        let mut pixels = vec![0u8; 4 * 4 * CHANNELS];
        for y in 0..4 {
            for x in 0..4 {
                let offset = (y * 4 + x) * CHANNELS;
                let value: u8 = if (x + y) % 2 == 0 { 255 } else { 0 };
                pixels[offset] = value;
                pixels[offset + 1] = value;
                pixels[offset + 2] = value;
                pixels[offset + 3] = 255;
            }
        }
        let (out, w, h) = downsample_box_filter(&pixels, 4, 4);
        assert_eq!((w, h), (2, 2));
        for texel in out.chunks_exact(CHANNELS) {
            assert!(texel[0] == 127 || texel[0] == 128);
        }
    }

    #[test]
    fn downsample_odd_dimensions_does_not_panic_and_clamps_edge_sampling() {
        let pixels = solid_rgba(5, 3, 10, 20, 30, 255);
        let (out, w, h) = downsample_box_filter(&pixels, 5, 3);
        assert_eq!((w, h), (2, 1));
        for texel in out.chunks_exact(CHANNELS) {
            assert_eq!(texel, &[10, 20, 30, 255]);
        }
    }

    #[test]
    fn downsample_1x1_stays_1x1() {
        let pixels = solid_rgba(1, 1, 5, 6, 7, 255);
        let (out, w, h) = downsample_box_filter(&pixels, 1, 1);
        assert_eq!((w, h), (1, 1));
        assert_eq!(out, vec![5, 6, 7, 255]);
    }

    #[test]
    fn downsample_alpha_channel_is_averaged_like_color_channels() {
        let mut pixels = vec![0u8; 2 * 1 * CHANNELS];
        pixels[0..CHANNELS].copy_from_slice(&[255, 255, 255, 255]);
        pixels[CHANNELS..CHANNELS * 2].copy_from_slice(&[255, 255, 255, 0]);
        let (out, w, h) = downsample_box_filter(&pixels, 2, 1);
        assert_eq!((w, h), (1, 1));
        assert_eq!(out[3], 128);
    }

    #[test]
    fn build_mip_chain_produces_requested_level_count() {
        let pixels = solid_rgba(16, 16, 1, 2, 3, 255);
        let chain = build_mip_chain(&pixels, 16, 16, 4);
        assert_eq!(chain.len(), 5);
        assert_eq!((chain[0].1, chain[0].2), (16, 16));
        assert_eq!((chain[1].1, chain[1].2), (8, 8));
        assert_eq!((chain[2].1, chain[2].2), (4, 4));
        assert_eq!((chain[3].1, chain[3].2), (2, 2));
        assert_eq!((chain[4].1, chain[4].2), (1, 1));
    }

    #[test]
    fn build_mip_chain_stops_early_at_1x1_even_if_max_level_is_higher() {
        let pixels = solid_rgba(4, 4, 9, 9, 9, 255);
        let chain = build_mip_chain(&pixels, 4, 4, 6);
        assert_eq!(chain.len(), 3);
        assert_eq!((chain.last().unwrap().1, chain.last().unwrap().2), (1, 1));
    }

    #[test]
    fn build_mip_chain_base_level_is_unmodified_copy_of_input() {
        let pixels = solid_rgba(8, 8, 42, 43, 44, 200);
        let chain = build_mip_chain(&pixels, 8, 8, 2);
        assert_eq!(chain[0].0, pixels);
    }

    #[test]
    fn build_mip_chain_with_zero_max_level_returns_only_base() {
        let pixels = solid_rgba(8, 8, 1, 1, 1, 255);
        let chain = build_mip_chain(&pixels, 8, 8, 0);
        assert_eq!(chain.len(), 1);
        assert_eq!((chain[0].1, chain[0].2), (8, 8));
    }

    #[test]
    fn build_mip_chain_non_power_of_two_base_still_terminates() {
        let pixels = solid_rgba(15, 22, 5, 5, 5, 255);
        let chain = build_mip_chain(&pixels, 15, 22, 8);
        for i in 1..chain.len() {
            let (_, prev_w, prev_h) = chain[i - 1];
            let (_, w, h) = chain[i];
            assert!(w <= prev_w && h <= prev_h);
        }
        let (_, last_w, last_h) = *chain.last().unwrap();
        assert!(last_w == 1 && last_h == 1 || chain.len() == 9);
    }

    fn make_texel_grid(width: u32, height: u32, alphas: &[u8]) -> Vec<u8> {
        assert_eq!(alphas.len(), (width * height) as usize);
        let pixel_count = (width * height) as usize;
        let mut pixels = vec![0u8; pixel_count * CHANNELS];
        for (i, &a) in alphas.iter().enumerate() {
            let offset = i * CHANNELS;
            pixels[offset] = 128;
            pixels[offset + 1] = 128;
            pixels[offset + 2] = 128;
            pixels[offset + 3] = a;
        }
        pixels
    }

    #[test]
    fn shape_all_255_is_fully_opaque() {
        let pixels = make_texel_grid(2, 2, &[255, 255, 255, 255]);
        assert_eq!(classify_alpha_shape(&pixels, 0, 2, 2), AlphaShape::FullyOpaque);
    }

    #[test]
    fn shape_mix_of_0_and_255_is_punch_through() {
        let pixels = make_texel_grid(2, 2, &[255, 0, 0, 255]);
        assert_eq!(classify_alpha_shape(&pixels, 0, 2, 2), AlphaShape::PunchThrough);
    }

    #[test]
    fn shape_all_0_is_punch_through_not_blended() {
        let pixels = make_texel_grid(2, 2, &[0, 0, 0, 0]);
        assert_eq!(classify_alpha_shape(&pixels, 0, 2, 2), AlphaShape::PunchThrough);
    }

    #[test]
    fn shape_single_partial_texel_among_binary_ones_is_blended() {
        let pixels = make_texel_grid(2, 2, &[255, 0, 128, 255]);
        assert_eq!(classify_alpha_shape(&pixels, 0, 2, 2), AlphaShape::Blended);
    }

    #[test]
    fn shape_short_circuits_on_first_blended_texel_scanning_row_major() {
        let pixels = make_texel_grid(3, 1, &[255, 200, 0]);
        assert_eq!(classify_alpha_shape(&pixels, 0, 3, 1), AlphaShape::Blended);
    }

    #[test]
    fn shape_classify_alpha_boolean_wrapper_agrees_with_shape_for_all_three_cases() {
        let opaque = make_texel_grid(2, 2, &[255, 255, 255, 255]);
        let punch_through = make_texel_grid(2, 2, &[255, 0, 0, 255]);
        let blended = make_texel_grid(2, 2, &[255, 0, 128, 255]);
        assert!(!classify_alpha(&opaque, 0, 2, 2));
        assert!(classify_alpha(&punch_through, 0, 2, 2));
        assert!(classify_alpha(&blended, 0, 2, 2));
    }

    #[test]
    fn shape_batch_matches_per_sprite_indices() {
        let (mut pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        for i in 0..(TEST_WIDTH * TEST_HEIGHT) as usize {
            if i % 2 == 0 {
                pixels[second_offset + i * CHANNELS + 3] = 0;
            }
        }
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, false);
        let shapes = classify_alpha_shape_batch(&pixels, &sprites);
        assert_eq!(shapes, vec![AlphaShape::FullyOpaque, AlphaShape::PunchThrough]);
    }

    #[test]
    fn shape_out_of_bounds_geometry_does_not_panic() {
        let pixels = vec![0u8; CHANNELS];
        assert_eq!(classify_alpha_shape(&pixels, 0, 10, 10), AlphaShape::FullyOpaque);
    }

    #[test]
    fn detect_families_and_assemble_populates_alpha_shapes_in_input_order() {
        let (mut pixels, second_offset) = duplicated_test_image(TEST_WIDTH, TEST_HEIGHT);
        for i in 0..(TEST_WIDTH * TEST_HEIGHT) as usize {
            if i % 2 == 0 {
                pixels[second_offset + i * CHANNELS + 3] = 0;
            }
        }
        let sprites = side_by_side_sprites(TEST_WIDTH, TEST_HEIGHT, second_offset, false);
        let result = detect_families_and_assemble(&pixels, &sprites, TEST_WIDTH * 2, TEST_HEIGHT, 4);
        assert_eq!(result.alpha_shapes, vec![AlphaShape::FullyOpaque, AlphaShape::PunchThrough]);
    }
}
