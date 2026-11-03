import csv
import json
import tempfile
import unittest
from pathlib import Path
from analyze_client_frame_budget import analyze
from compare_client_frames import compare
from test_client_frame_gate import ClientGateTests


class AttributionTests(unittest.TestCase):
    def test_rift_query_is_a_child_of_visual_whole(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'frames.json';p.write_text('{}')
            p.with_suffix('.csv').write_text('scene_frame,published_generation,interval_ms,gpu_ms\n1,2,20,19\n')
            stages={'iris.translucents.begin':4,'iris.translucents.depth-copy':.1,'iris.whole-stage.deferred':1.5,'irisveil.before-deferred.callbacks':.4,'rift.visual.whole':1.8,'rift.visibility.depth':.1,'rift.target.whole':1.5,'rift.target.deferred':1}
            p.with_name('frames-stages.csv').write_text('scene_frame,stage,generation,gpu_ms\n'+''.join(f'1,{k},-1,{v}\n' for k,v in stages.items()))
            result=analyze(p)['same_frame_differences']
            self.assertAlmostEqual(result['begin_outside_rift_ms']['p50'],.2)
            self.assertAlmostEqual(result['rift_visual_outside_target_ms']['p50'],.2)
    def test_begin_residual_uses_same_frame_siblings_and_keeps_missing_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'frames.json';p.write_text('{}')
            p.with_suffix('.csv').write_text('scene_frame,published_generation,interval_ms,gpu_ms\n1,2,20,19\n2,2,20,19\n')
            p.with_name('frames-stages.csv').write_text('scene_frame,stage,generation,gpu_ms\n1,iris.translucents.begin,-1,4\n1,iris.translucents.depth-copy,-1,0.1\n1,iris.whole-stage.deferred,-1,1.5\n1,irisveil.before-deferred.callbacks,-1,0.4\n1,rift.target.whole,-1,2\n1,rift.target.deferred,-1,1\n2,iris.translucents.begin,-1,4\n')
            out=Path(directory)/'derived.csv';result=analyze(p,out)
            self.assertEqual(result['same_frame_differences']['begin_unexplained_ms'],dict(samples=1,p50=2,p95=2,p99=2))
            with out.open() as stream:rows=list(csv.DictReader(stream))
            self.assertEqual(rows[1]['begin_unexplained_ms'],'')

    def test_partial_gpu_range_totals_are_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'frames.json';p.write_text('{}')
            p.with_suffix('.csv').write_text('scene_frame,published_generation,interval_ms,gpu_ms\n1,2,20,19\n')
            p.with_name('frames-stages.csv').write_text('scene_frame,stage,generation,gpu_ms,cpu_ranges,gpu_ranges\n1,world.render,-1,18,1,1\n1,world.section.solid_particles,-1,5,3,2\n1,scene.compute,-1,3,0,1\n')
            result=analyze(p)
            self.assertEqual(result['stages']['world.section.solid_particles'],{'samples':0})
            self.assertEqual(result['world_minus_reported_sections_ms'],{'samples':0})
            self.assertEqual(result['stages']['scene.compute']['p50'],3)

    def test_equivalent_yaw_and_real_orientation_changes(self):
        from compare_client_frames import camera_matches
        self.assertTrue(camera_matches([1,2,3,-537.2456,15,70],[1,2,3,-177.2456,15,70]))
        self.assertTrue(camera_matches([1,2,3,179.9999,15,70],[1,2,3,-180.0001,15,70]))
        self.assertFalse(camera_matches([1,2,3,1,15,70],[1,2,3,2,15,70]))
        self.assertFalse(camera_matches([1,2,3,1,15,70],[1,2,3,1,16,70]))

    def test_production_gate_accepts_full_turn_yaw_but_rejects_unknown_counts(self):
        for unknown in (False,True):
            with self.subTest(unknown=unknown),tempfile.TemporaryDirectory() as directory:
                manifest=self.production_fixture(Path(directory))
                for group in manifest['rounds']:
                    for filename in group['candidate_aa']:
                        p=Path(filename);camera=p.with_name(p.stem+'-camera.csv')
                        camera.write_text(camera.read_text().replace(',0,0,70',',360,0,70'))
                        if unknown:p.with_suffix('.csv').write_text(p.with_suffix('.csv').read_text().replace('65536','-1'))
                if unknown:
                    with self.assertRaises(ValueError):compare(manifest)
                else:self.assertTrue(compare(manifest)['frame_gate_pass'])

    def test_missing_shadow_is_unknown_and_children_are_not_summed(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)/'frames.json'
            p.write_text('{}')
            p.with_suffix('.csv').write_text('scene_frame,published_generation,interval_ms,gpu_ms\n1,2,20,19\n2,2,20,19\n')
            p.with_name('frames-stages.csv').write_text('scene_frame,stage,generation,gpu_ms\n1,scene.compute,-1,3\n1,draw.pack.merge,2,5\n1,draw.pack.model-shadow,2,1\n1,draw.pack.model-shadow.draw,2,1\n2,scene.compute,-1,3\n2,draw.pack.merge,2,5\n')
            result = analyze(p)
            self.assertEqual(result['uncovered_ms'], dict(samples=1,p50=10,p95=10,p99=10))
            self.assertEqual(result['outside_compute_ms']['samples'], 2)
            self.assertAlmostEqual(result['required_saving_ms_for_20_percent'],20/6)

    def test_world_parent_is_not_added_to_its_children(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'frames.json';p.write_text('{}')
            p.with_suffix('.csv').write_text('scene_frame,published_generation,interval_ms,gpu_ms\n1,2,20,19\n')
            p.with_name('frames-stages.csv').write_text('scene_frame,stage,generation,gpu_ms\n1,world.render,-1,18\n1,world.section.entities,-1,5\n1,world.section.blockentities,-1,6\n1,world.terrain.solid,-1,4\n')
            result=analyze(p)
            self.assertEqual(result['outside_world_ms']['p50'],1)
            self.assertEqual(result['world_minus_reported_sections_ms']['p50'],7)

    def production_fixture(self, root, candidate_ms=32):
        manifest = ClientGateTests().fixture(root)
        manifest['comparison'] = 'production-builds'
        manifest['builds'] = {side: dict(build_sha256=sha*64,engine_source_sha256='c'*64) for side,sha in (('baseline','a'),('candidate','b'))}
        for index,r in enumerate(manifest['rounds']):
            for side, paths in (('baseline',r['aa']),('candidate',r['candidate_aa'])):
                for filename in paths:
                    p=Path(filename);m=json.loads(p.read_text());m.update(manifest['builds'][side])
                    m.update(backend='production-opengl',diagnostic_receipt_sha256='none',fps_limit=260,
                             process_id=index*2+(1 if side=='baseline' else 2),process_started=f'round-{index}-{side}',
                             measurement_mode='instrumented-frame-window',shadow_quality='FULL',
                             context='pack=fixture; options=abc; engine='+('c'*64)+'; shadow_quality=FULL')
                    ms=40 if side=='baseline' else candidate_ms
                    m['measured_seconds']=ms;p.write_text(json.dumps(m))
                    with p.with_suffix('.csv').open('w',newline='') as stream:
                        w=csv.writer(stream);w.writerow(('scene_frame','interval_ms','live','model_input','attack_contract_stage'))
                        w.writerows((i,ms,65536,65536,'') for i in range(1000))
        return manifest

    def test_production_build_gate_at_20_percent_and_below(self):
        for ms, passed in ((32,True),(34,False)):
            with self.subTest(ms=ms),tempfile.TemporaryDirectory() as directory:
                manifest=self.production_fixture(Path(directory),ms)
                self.assertEqual(compare(manifest)['frame_gate_pass'],passed)

    def test_identity_quality_options_and_process_mismatches_fail(self):
        for key,value in (('build_sha256','d'*64),('shadow_quality','OFF'),('context','pack=other'),('measurement_mode','other'),('vsync',True)):
            with self.subTest(key=key),tempfile.TemporaryDirectory() as directory:
                manifest=self.production_fixture(Path(directory));p=Path(manifest['rounds'][0]['ab'][1])
                m=json.loads(p.read_text());m[key]=value;p.write_text(json.dumps(m))
                with self.assertRaises(ValueError):compare(manifest)

    def test_reusing_aa_window_is_not_a_noise_control(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest=self.production_fixture(Path(directory))
            manifest['rounds'][0]['aa'][1]=manifest['rounds'][0]['aa'][0]
            with self.assertRaises(ValueError):compare(manifest)

    def test_rift_configuration_must_match_within_each_build(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest=self.production_fixture(Path(directory))
            for group in manifest['rounds']:
                for filename in group['candidate_aa']:
                    p=Path(filename);m=json.loads(p.read_text());m['rift_config']={'clipping':True,'culling':'FRUSTUM_DEPTH'};p.write_text(json.dumps(m))
            self.assertTrue(compare(manifest)['frame_gate_pass'])
            p=Path(manifest['rounds'][0]['candidate_aa'][0]);m=json.loads(p.read_text());m['rift_config']['clipping']=False;p.write_text(json.dumps(m))
            with self.assertRaisesRegex(ValueError,'rift configuration'):compare(manifest)

    def test_p95_regression_blocks_an_average_fps_gain(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest=self.production_fixture(Path(directory))
            # Separate AB window: candidate A/A remains stable while AB develops a tail.
            source=Path(manifest['rounds'][0]['ab'][1]);target=source.with_name('tail.json')
            m=json.loads(source.read_text());m['measured_seconds']=30*.9+60*.1;target.write_text(json.dumps(m))
            target.with_name('tail-camera.csv').write_text(source.with_name(source.stem+'-camera.csv').read_text())
            with target.with_suffix('.csv').open('w',newline='') as stream:
                w=csv.writer(stream);w.writerow(('scene_frame','interval_ms','live','model_input','attack_contract_stage'))
                w.writerows((i,30 if i<900 else 60,65536,65536,'') for i in range(1000))
            manifest['rounds'][0]['ab'][1]=str(target)
            result=compare(manifest)
            self.assertGreater(result['rounds'][0]['average_fps_gain'],.2)
            self.assertFalse(result['frame_gate_pass'])


if __name__ == '__main__':
    unittest.main()
