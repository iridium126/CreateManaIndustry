import math
import struct
import unittest
from neighbor_reference import Member,candidate_batches,complete_candidates,most_dangerous,reduction_error


class NeighborReferenceTest(unittest.TestCase):
    def test_full_dense_query_exceeds_both_old_budgets_without_loss(self):
        members=[Member(i,(0.,0.,0.)) for i in range(1001)]
        chunks=list(candidate_batches(members,members[:3],1.,0.,31))
        for query in members[:3]:
            rows=[r for r in chunks if r[0]==query.identity]
            self.assertEqual(sum(r[1] for r in rows),1)
            result=[i for _,_,chunk in rows for i in chunk]
            self.assertEqual(set(result),set(range(1001))-{query.identity})
            self.assertEqual(len(result),1000)
            self.assertTrue(all(len(row[2])<=31 for row in rows))

    def test_swept_crossing_is_not_rejected_by_start_distance(self):
        a=Member(4,(-10.,0.,0.),(20.,0.,0.));b=Member(8,(10.,0.,0.),(-20.,0.,0.))
        self.assertEqual(list(complete_candidates([a,b],a,.35,1)),[8])
        self.assertEqual(list(complete_candidates([a,b],a,.35,0)),[])

    def test_self_dead_duplicate_and_exact_boundary(self):
        a=Member(9,(0.,0.,0.));b=Member(1,(1.,0.,0.));dead=Member(2,(0.,0.,0.),applicable=False)
        self.assertEqual(list(complete_candidates([a,b,dead],a,1)),[1])
        with self.assertRaises(ValueError):list(candidate_batches([a,a],[a],1,0,10))
        self.assertEqual(list(candidate_batches([a],[a],1,0,10)),[(9,True,())])

    def test_contact_ties_use_stable_identity_in_any_order(self):
        contacts=[(15,3.),(4,3.),(1,0.),(7,2.)]
        self.assertEqual(most_dangerous(contacts),(4,3.))
        self.assertEqual(most_dangerous(reversed(contacts)),(4,3.))
        with self.assertRaises(ValueError):most_dangerous([(1,math.nan)])

    def test_fp32_reduction_accepts_rounding_but_rejects_lost_force(self):
        terms=[1.,2**-24,-1.,2**-24]*1000
        value=0.
        for term in terms:value=struct.unpack('<f',struct.pack('<f',value+term))[0]
        error,bound=reduction_error(terms,value);self.assertLessEqual(error,bound)
        error,bound=reduction_error([.25,.5],0);self.assertGreater(error,bound)
        with self.assertRaises(ValueError):reduction_error([math.nan],0)


if __name__=='__main__':unittest.main()
