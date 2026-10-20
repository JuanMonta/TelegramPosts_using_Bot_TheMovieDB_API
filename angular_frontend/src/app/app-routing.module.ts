import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { HomeComponent } from './components/home/home.component';
import { PublicacionComponent } from './components/publicacion/publicacion.component';
import { PostComponent } from './components/post/post.component';

const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'publicacion/:id', component: PublicacionComponent },
  { path: 'post/:id', component: PostComponent },
  { path: '**', redirectTo: '', pathMatch: 'full' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }