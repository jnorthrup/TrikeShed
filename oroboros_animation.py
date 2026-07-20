from manim import *
import numpy as np

# ──────────────────────────────────────────────────────────────────────
# OROBOROS ANIMATION — Self-referential CAS + Version + Network cycle
# ──────────────────────────────────────────────────────────────────────

BG = "#0A0A0A"
PRIMARY = "#00F5FF"      # cyan — CAS / Storage
SECONDARY = "#FF6B6B"    # coral — Version / Git
ACCENT = "#39FF14"       # neon green — Network / DHT
MUTED = "#555555"
MONO = "Menlo"

class OroborosAnimation(Scene):
    def construct(self):
        self.camera.background_color = BG
        
        # Title
        title = Text("Oroboros: CAS ⟲ Version ⟲ Network", font_size=40, font=MONO, weight=BOLD, color=PRIMARY)
        title.to_edge(UP, buff=0.5)
        self.play(Write(title, run_time=1.5))
        self.wait(1)
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 1: Three nodes forming a cycle
        # ─────────────────────────────────────────────────────────────
        
        # Three circles at 120°
        radius = 2.0
        centers = [
            radius * UP,                                    # Storage (top)
            radius * (DOWN * 0.5 + LEFT * 0.866),           # Version (bottom-left)
            radius * (DOWN * 0.5 + RIGHT * 0.866),          # Network (bottom-right)
        ]
        colors = [PRIMARY, SECONDARY, ACCENT]
        labels = ["STORAGE\n(CAS)", "VERSION\n(Git/Pijul)", "NETWORK\n(DHT/HTX)"]
        icons = ["🗄", "🌿", "📡"]
        
        nodes = VGroup()
        for i, (center, color, label, icon) in enumerate(zip(centers, colors, labels, icons)):
            circle = Circle(radius=0.9, color=color, stroke_width=3)
            circle.move_to(center)
            
            icon_text = Text(icon, font_size=36)
            icon_text.move_to(center + UP * 0.3)
            
            label_text = Text(label, font_size=18, font=MONO, color=color, line_spacing=0.8)
            label_text.move_to(center + DOWN * 0.4)
            
            node = VGroup(circle, icon_text, label_text)
            nodes.add(node)
        
        # Arrows forming cycle
        arrows = VGroup()
        for i in range(3):
            start = centers[i]
            end = centers[(i + 1) % 3]
            
            # Curved arrow
            angle = PI / 3 if i == 0 else (-PI / 3 if i == 1 else PI)
            arc = ArcBetweenPoints(
                start + (end - start) * 0.2,
                end + (start - end) * 0.2,
                angle=angle,
                color=MUTED,
                stroke_width=2
            )
            arrow_tip = ArrowTriangleFilledTip(start_angle=0, color=MUTED).scale(0.15)
            arrow_tip.move_to(arc.get_end())
            arrow_tip.rotate(arc.get_angle() + PI/2)
            
            arrow_label = Text(
                ["put/ref\nlink", "commit\nrecord", "fetch/\nfanout"][i],
                font_size=12, font=MONO, color=MUTED
            )
            mid = arc.point_from_proportion(0.5)
            if i == 0:
                arrow_label.move_to(mid + UP * 0.4)
            elif i == 1:
                arrow_label.move_to(mid + LEFT * 0.4)
            else:
                arrow_label.move_to(mid + RIGHT * 0.4)
            
            arrows.add(VGroup(arc, arrow_tip, arrow_label))
        
        # Animate nodes appearing
        for node in nodes:
            self.play(FadeIn(node, scale=0.8), run_time=0.6)
            self.wait(0.2)
        
        # Animate arrows forming cycle
        for arrow in arrows:
            self.play(Create(arrow[0]), FadeIn(arrow[2]), run_time=0.8)
            self.play(FadeIn(arrow[1]), run_time=0.3)
        
        self.wait(1)
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 2: Mutation flow through the cycle
        # ─────────────────────────────────────────────────────────────
        
        mutation = Text("Mutation: upsert('/src/main.kt', content)", font_size=20, font=MONO, color=ACCENT)
        mutation.next_to(title, DOWN, buff=0.3)
        self.play(Write(mutation))
        self.wait(0.5)
        
        # Particle flowing through cycle
        particle = Dot(radius=0.12, color=ACCENT, fill_opacity=1)
        particle.move_to(centers[0] + UP * 0.5)
        
        # Path along the cycle
        path1 = ArcBetweenPoints(
            centers[0] + DOWN * 0.2,
            centers[1] + UP * 0.2,
            angle=PI/3
        )
        path2 = ArcBetweenPoints(
            centers[1] + RIGHT * 0.2,
            centers[2] + LEFT * 0.2,
            angle=PI/3
        )
        path3 = ArcBetweenPoints(
            centers[2] + UP * 0.2,
            centers[0] + DOWN * 0.2,
            angle=PI/3
        )
        
        self.play(FadeIn(particle))
        self.play(MoveAlongPath(particle, path1), run_time=1.2)
        self.play(MoveAlongPath(particle, path2), run_time=1.2)
        self.play(MoveAlongPath(particle, path3), run_time=1.2)
        self.play(FadeOut(particle))
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 3: Idempotent replay - same mutation, no advance
        # ─────────────────────────────────────────────────────────────
        
        idempotent_text = Text("Replay same mutation → idempotent hit (seq unchanged)", font_size=18, font=MONO, color=SECONDARY)
        idempotent_text.next_to(title, DOWN, buff=0.3)
        self.play(ReplacementTransform(mutation, idempotent_text))
        self.wait(0.5)
        
        # Particle pulses but doesn't advance
        particle2 = Dot(radius=0.12, color=SECONDARY, fill_opacity=1)
        particle2.move_to(centers[0] + UP * 0.5)
        self.play(FadeIn(particle2))
        
        for _ in range(3):
            self.play(particle2.animate.scale(1.5).set_opacity(0.3), run_time=0.3)
            self.play(particle2.animate.scale(1/1.5).set_opacity(1), run_time=0.3)
        
        self.play(FadeOut(particle2))
        self.wait(0.5)
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 4: CAS deduplication visual
        # ─────────────────────────────────────────────────────────────
        
        dedup_text = Text("CAS dedup: identical content → single extent (reflink)", font_size=18, font=MONO, color=PRIMARY)
        dedup_text.next_to(title, DOWN, buff=0.3)
        self.play(ReplacementTransform(idempotent_text, dedup_text))
        
        # Show two files pointing to same block
        file1 = Rectangle(width=1.2, height=0.5, color=PRIMARY, fill_opacity=0.2)
        file1.move_to(centers[0] + LEFT * 2.5)
        file1_label = Text("fileA", font_size=14, font=MONO, color=PRIMARY).next_to(file1, LEFT)
        
        file2 = Rectangle(width=1.2, height=0.5, color=PRIMARY, fill_opacity=0.2)
        file2.move_to(centers[0] + LEFT * 2.5 + DOWN * 0.7)
        file2_label = Text("fileB", font_size=14, font=MONO, color=PRIMARY).next_to(file2, LEFT)
        
        block = Rectangle(width=1.2, height=0.5, color=ACCENT, fill_opacity=0.8)
        block.move_to(centers[0] + RIGHT * 2.5)
        block_label = Text("CID: abc123...", font_size=14, font=MONO, color=ACCENT).next_to(block, RIGHT)
        
        arrow1 = Arrow(file1.get_right(), block.get_left(), color=PRIMARY, buff=0.1)
        arrow2 = Arrow(file2.get_right(), block.get_left(), color=PRIMARY, buff=0.1)
        
        self.play(FadeIn(VGroup(file1, file1_label, file2, file2_label)))
        self.play(FadeIn(VGroup(block, block_label)))
        self.play(Create(arrow1), Create(arrow2))
        self.wait(1)
        
        # Show dedup ratio
        ratio = Text("dedup ratio: 2.3×", font_size=24, font=MONO, weight=BOLD, color=ACCENT)
        ratio.move_to(centers[0] + RIGHT * 4.5)
        self.play(Write(ratio))
        self.wait(1)
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 5: Btrfs reflink cycle
        # ─────────────────────────────────────────────────────────────
        
        btrfs_text = Text("btrfs reflink: COW snapshots + extent dedup", font_size=18, font=MONO, color=ACCENT)
        btrfs_text.next_to(title, DOWN, buff=0.3)
        self.play(ReplacementTransform(dedup_text, btrfs_text))
        
        # Show snapshot tree
        snap_root = Text("snapshot@1234\n(root CID)", font_size=16, font=MONO, color=PRIMARY)
        snap_root.move_to(centers[0] + LEFT * 3.5 + UP * 1.5)
        
        snap_child1 = Text("fileA → CID:abc", font_size=14, font=MONO, color=MUTED)
        snap_child1.move_to(centers[0] + LEFT * 3.5 + UP * 0.8)
        
        snap_child2 = Text("fileB → CID:abc (shared)", font_size=14, font=MONO, color=ACCENT)
        snap_child2.move_to(centers[0] + LEFT * 3.5 + UP * 0.1)
        
        snap_child3 = Text("fileC → CID:def", font_size=14, font=MONO, color=MUTED)
        snap_child3.move_to(centers[0] + LEFT * 3.5 + DOWN * 0.6)
        
        self.play(FadeIn(VGroup(snap_root, snap_child1, snap_child2, snap_child3)))
        self.wait(1)
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 6: Full cycle with data flow
        # ─────────────────────────────────────────────────────────────
        
        flow_text = Text("Full cycle: upsert → commit → fanout → fetch → verify", font_size=18, font=MONO, color=ACCENT)
        flow_text.next_to(title, DOWN, buff=0.3)
        self.play(ReplacementTransform(btrfs_text, flow_text))
        
        # Animate particles flowing through all three nodes simultaneously
        particles = VGroup()
        for i in range(3):
            p = Dot(radius=0.1, color=[PRIMARY, SECONDARY, ACCENT][i], fill_opacity=1)
            p.move_to(centers[i] + UP * 0.5)
            particles.add(p)
        
        # Create paths for each particle
        paths = []
        for i in range(3):
            start = centers[i]
            end = centers[(i + 1) % 3]
            angle = PI / 3 if i == 0 else (-PI / 3 if i == 1 else PI)
            path = ArcBetweenPoints(
                start + (end - start) * 0.2,
                end + (start - end) * 0.2,
                angle=angle
            )
            paths.append(path)
        
        self.play(FadeIn(particles))
        self.play(*[MoveAlongPath(p, path) for p, path in zip(particles, paths)], run_time=1.5)
        self.play(*[MoveAlongPath(p, paths[(i+1)%3]) for i, p in enumerate(particles)], run_time=1.5)
        self.play(*[MoveAlongPath(p, paths[(i+2)%3]) for i, p in enumerate(particles)], run_time=1.5)
        self.play(FadeOut(particles))
        
        self.wait(1)
        
        # ─────────────────────────────────────────────────────────────
        # SCENE 7: Final summary
        # ─────────────────────────────────────────────────────────────
        
        summary_lines = [
            "Oroboros: Single ingress, three facets, zero coordination",
            "CAS deduplicates → Version records → Network fans out",
            "Idempotent by design • CRDT patches • btrfs-backed",
        ]
        
        summary = VGroup(*[
            Text(line, font_size=18, font=MONO, color=WHITE) for line in summary_lines
        ]).arrange(DOWN, aligned_edge=LEFT, buff=0.3)
        summary.next_to(title, DOWN, buff=1.5)
        
        self.play(ReplacementTransform(flow_text, summary[0]))
        for i in range(1, len(summary_lines)):
            self.play(Write(summary[i]))
            self.wait(0.3)
        
        self.wait(2)
        
        # Fade out
        self.play(
            FadeOut(title),
            FadeOut(summary),
            FadeOut(nodes),
            FadeOut(arrows),
            run_time=1
        )


if __name__ == "__main__":
    import sys
    sys.argv = ["manim", "-qh", __file__, "OroborosAnimation"]
    from manim import config
    config.media_width = "1920"
    config.media_height = "1080"
    config.frame_rate = 60
    from manim.__main__ import main
    main()