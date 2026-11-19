# Masaniello-style progression check: stake sized so that, from state (n trades left, k wins needed),
# remaining capital reaches the target if exactly k wins come. Standard recursion with payout q (decimal odds).
import random, functools, math
def run(p, N=10, K=4, q=1.79, cap=200.0, sims=200000, seed=1):
    @functools.lru_cache(None)
    def f(n,k):
        # multiplier of current capital achievable when n left, k wins needed (fair Masaniello value)
        if k==0: return 1.0
        if k>n: return 0.0
        a=f(n-1,k-1); b=f(n-1,k)
        # choose stake fraction s: win -> (1+s(q-1))*a', lose -> (1-s)*b' equalised
        # value v satisfies v = 1/( (1/q)/a + (1-1/q)/b ) when b>0
        if b==0: return q**0 * a* q if False else a*q if n==k and False else (1.0/((1.0/q)/a))
        return 1.0/((1.0/q)/a + (1-1.0/q)/b)
    rnd=random.Random(seed); tot=0; busts=0
    target=f(N,K)
    for _ in range(sims):
        c=cap; n=N; k=K
        while k>0 and n>=k and c>1e-9:
            a=f(n-1,k-1); b=f(n-1,k); v=f(n,k)
            # stake fraction so that win->c*v/a? we need c_after_win * a = c*v and c_after_loss*b = c*v
            s=(v/a-1)/(q-1)
            st=c*s
            if rnd.random()<p: c+=st*(q-1); k-=1
            else: c-=st
            n-=1
        if k>0: busts+=1
        tot+=c-cap
    return target*cap-cap, tot/sims, busts/sims
for p in (0.40,0.50,0.559,0.60,0.70):
    t,ev,b=run(p)
    print(f"p={p:.3f} profit-if-4-wins={t:7.2f}  EV/session={ev:8.2f}  sessions that lose all $200={b:.1%}")
t,ev,b=run(0.5,K=5); print("K=5 p=0.5 target",round(t,2),"EV",round(ev,2),"bust",f"{b:.1%}")
