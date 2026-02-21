
import React from 'react';
import {
  Search, Bell, Settings, Plus, Star, Database,
  ChevronRight, LayoutGrid, Users, Mail, CreditCard,
  Briefcase, Activity, Ticket, ShoppingCart, Package,
  MessageSquare, CheckSquare, Users2
} from 'lucide-react';
import type { DataRow } from './types';

const SubscriptionType: any = {}

export const MOCK_CONTACTS: DataRow[] = [
  {
    id: 1,
    contactName: "Zain Lubin",
    contactId: 321,
    title: "Manager",
    profilePicture: "https://picsum.photos/seed/1/32/32",
    email: "zlubin@gmail.com",
    subscription: SubscriptionType.PRO,
    registrationNumber: "+8207461130782",
    paidMember: false,
    postalAddress: "2400 Route 9, Fishkill NY 12524"
  },
  {
    id: 2,
    contactName: "Kierra Westervelt",
    contactId: 322,
    title: "Director",
    profilePicture: "https://picsum.photos/seed/2/32/32",
    email: "kierraw@outlook.com",
    subscription: SubscriptionType.PLUS,
    registrationNumber: "+7047091633321",
    paidMember: true,
    postalAddress: "200 Otis Street, Northboro MA 1532"
  },
  {
    id: 3,
    contactName: "Wilson Curtis",
    contactId: 323,
    title: "Assistant",
    profilePicture: "https://picsum.photos/seed/3/32/32",
    email: "wilcurtis@outlook.com",
    subscription: SubscriptionType.PRIME,
    registrationNumber: "+7893981497100",
    paidMember: true,
    postalAddress: "30 Catskill, Catskill NY 12414"
  },
  {
    id: 4,
    contactName: "Emerson Dokidis",
    contactId: 324,
    title: "VP of Sales",
    profilePicture: "https://picsum.photos/seed/4/32/32",
    email: "emerson12@gmail.com",
    subscription: SubscriptionType.PRO,
    registrationNumber: "+3080455855339",
    paidMember: false,
    postalAddress: "100 Elm Ridge Center Dr, Greece NY 14612"
  },
  {
    id: 5,
    contactName: "Alfredo Westervelt",
    contactId: 325,
    title: "Engineer",
    profilePicture: "https://picsum.photos/seed/5/32/32",
    email: "alfredo.pasta@gmail.com",
    subscription: SubscriptionType.PRO,
    registrationNumber: "+2081017757126",
    paidMember: false,
    postalAddress: "601 Frank Stottile Blvd, Kingston NY 12401"
  },
  {
    id: 6,
    contactName: "Terry Bator",
    contactId: 326,
    title: "HR Manager",
    profilePicture: "https://picsum.photos/seed/6/32/32",
    email: "terryb@outlook.com",
    subscription: SubscriptionType.PLUS,
    registrationNumber: "+2173332610583",
    paidMember: true,
    postalAddress: "700 Oak Street, Brockton MA 02301"
  },
  {
    id: 7,
    contactName: "Maria Geidt",
    contactId: 327,
    title: "CEO",
    profilePicture: "https://picsum.photos/seed/7/32/32",
    email: "maria@gmail.com",
    subscription: SubscriptionType.PRO,
    registrationNumber: "+3410278098922",
    paidMember: false,
    postalAddress: "100 Thruway Plaza, Cheektowaga NY 14225"
  },
  {
    id: 8,
    contactName: "Tatiana Bergson",
    contactId: 328,
    title: "CFO",
    profilePicture: "https://picsum.photos/seed/8/32/32",
    email: "tatiana@outlook.com",
    subscription: SubscriptionType.PRIME,
    registrationNumber: "+5641767475164",
    paidMember: true,
    postalAddress: "72 Main St, North Reading MA 01864"
  },
  {
    id: 9,
    contactName: "Anika Bergson",
    contactId: 329,
    title: "Product Manager",
    profilePicture: "https://picsum.photos/seed/9/32/32",
    email: "abergson34@outlook.com",
    subscription: SubscriptionType.PLUS,
    registrationNumber: "+5780276410651",
    paidMember: false,
    postalAddress: "103 North Caroline St, Baltimore MD 21231"
  }
];

// Generate more data for a fuller feel
for (let i = 10; i <= 50; i++) {
  MOCK_CONTACTS.push({
    id: i,
    contactName: `User ${i}`,
    contactId: 330 + i,
    title: ["Analyst", "Developer", "Designer", "Support", "Marketing"][i % 5],
    profilePicture: `https://picsum.photos/seed/${i}/32/32`,
    email: `user${i}@example.com`,
    subscription: [SubscriptionType.PRO, SubscriptionType.PLUS, SubscriptionType.PRIME][i % 3],
    registrationNumber: `+123456789${i}`,
    paidMember: i % 2 === 0,
    postalAddress: `${i} Main St, Suite ${i}, New York NY 10001`
  });
}

